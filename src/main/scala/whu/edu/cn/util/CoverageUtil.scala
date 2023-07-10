package whu.edu.cn.util

import geotrellis.layer._
import geotrellis.proj4.CRS
import geotrellis.raster.mapalgebra.focal
import geotrellis.raster.mapalgebra.focal.{Neighborhood, TargetCell}
import geotrellis.raster.{CellType, GridBounds, MultibandTile, TargetCell, Tile, TileLayout}
import geotrellis.spark._
import geotrellis.vector.Extent
import org.apache.spark.rdd.RDD
import whu.edu.cn.entity.{RawTile, SpaceTimeBandKey}
import whu.edu.cn.util.TileSerializerCoverageUtil.deserializeTileData

import java.time.{Instant, ZoneOffset}
import scala.collection.mutable
import scala.math.{max, min}

object CoverageUtil {
  // TODO: lrx: 函数的RDD大写，变量的Rdd小写，为了开源全局改名，提升代码质量
  def makeCoverageRDD(tileRDDReP: RDD[RawTile]): (RDD[(SpaceTimeBandKey, MultibandTile)], TileLayerMetadata[SpaceTimeKey]) = {
    val coverageRawTiles: RDD[RawTile] = tileRDDReP
    val extents: (Double, Double, Double, Double) = coverageRawTiles.map(t => {
      (t.getExtent.xmin, t.getExtent.ymin, t.getExtent.xmax, t.getExtent.ymax)
    }).reduce((a, b) => {
      (min(a._1, b._1), min(a._2, b._2), max(a._3, b._3), max(a._4, b._4))
    })
    val colRowInstant: (Int, Int, Long, Int, Int, Long) = coverageRawTiles.map(t => {
      (t.getSpatialKey.col, t.getSpatialKey.row, t.getTime.toEpochSecond(ZoneOffset.ofHours(0)), t.getSpatialKey.col, t.getSpatialKey.row, t.getTime.toEpochSecond(ZoneOffset.ofHours(0)))
    }).reduce((a, b) => {
      (min(a._1, b._1), min(a._2, b._2), min(a._3, b._3), max(a._4, b._4), max(a._5, b._5), max(a._6, b._6))
    })
    //    if (colRowInstant._1 != 0 || colRowInstant._2 != 0 || (colRowInstant._3 != colRowInstant._6)) {
    //      throw new InternalError(s"内部错误！瓦片序号出错或时间不一致！请查看$colRowInstant")
    //    }


    val firstTile: RawTile = coverageRawTiles.first()
    val layoutCols: Int = math.max(math.ceil((extents._3 - extents._1) / firstTile.getResolutionCol / 256.0).toInt, 1)
    val layoutRows: Int = math.max(math.ceil((extents._4 - extents._2) / firstTile.getResolutionRow / 256.0).toInt, 1)

    val extent: Extent = geotrellis.vector.Extent(extents._1, extents._2, extents._1 + layoutCols * firstTile.getResolutionCol * 256.0, extents._2 + layoutRows * firstTile.getResolutionRow * 256.0)

    val tl: TileLayout = TileLayout(layoutCols, layoutRows, 256, 256)
    val ld: LayoutDefinition = LayoutDefinition(extent, tl)
    val cellType: CellType = CellType.fromName(firstTile.getDataType.toString)
    val crs: CRS = firstTile.getCrs
    val bounds: Bounds[SpaceTimeKey] = Bounds(SpaceTimeKey(0, 0, colRowInstant._3), SpaceTimeKey(colRowInstant._4 - colRowInstant._1, colRowInstant._5 - colRowInstant._2, colRowInstant._6))
    val tileLayerMetadata: TileLayerMetadata[SpaceTimeKey] = TileLayerMetadata(cellType, ld, extent, crs, bounds)

    val tileRdd: RDD[(SpaceTimeKey, (Int, String, Tile))] = coverageRawTiles.map(tile => {
      val phenomenonTime: Long = tile.getTime.toEpochSecond(ZoneOffset.ofHours(0))
      val rowNum: Int = tile.getSpatialKey.row
      val colNum: Int = tile.getSpatialKey.col
      val measurementRank: Int = tile.getMeasurementRank
      val measurement: String = tile.getMeasurement
      val Tile: Tile = deserializeTileData("", tile.getTileBuf, 256, tile.getDataType.toString)
      val k: SpaceTimeKey = SpaceTimeKey(colNum, rowNum, phenomenonTime)
      val v: Tile = Tile
      (k, (measurementRank, measurement, v))
    })
    val multibandTileRdd: RDD[(SpaceTimeBandKey, MultibandTile)] = tileRdd.groupByKey.map(t => {
      val tileSorted: Array[(Int, String, Tile)] = t._2.toArray.sortBy(x => x._1)
      val listBuffer: mutable.ListBuffer[String] = mutable.ListBuffer.empty[String]
      val tileMeasurement: Array[String] = tileSorted.map(x => x._2)
      listBuffer ++= tileMeasurement
      val tileArray: Array[Tile] = tileSorted.map(x => x._3)
      (SpaceTimeBandKey(t._1, listBuffer), MultibandTile(tileArray))
    })
    (multibandTileRdd, tileLayerMetadata)
  }


  def checkProjResoExtent(coverage1: (RDD[(SpaceTimeBandKey, MultibandTile)], TileLayerMetadata[SpaceTimeKey]), coverage2: (RDD[(SpaceTimeBandKey, MultibandTile)], TileLayerMetadata[SpaceTimeKey])): ((RDD[(SpaceTimeBandKey, MultibandTile)], TileLayerMetadata[SpaceTimeKey]), (RDD[(SpaceTimeBandKey, MultibandTile)], TileLayerMetadata[SpaceTimeKey])) = {
    val tile1: (SpaceTimeBandKey, MultibandTile) = coverage1._1.first()
    val tile2: (SpaceTimeBandKey, MultibandTile) = coverage2._1.first()
    val time1: Long = tile1._1.spaceTimeKey.instant
    val time2: Long = tile2._1.spaceTimeKey.instant
    val band1: mutable.ListBuffer[String] = tile1._1.measurementName
    val band2: mutable.ListBuffer[String] = tile2._1.measurementName
    val coverage1tileRDD: RDD[(SpatialKey, MultibandTile)] = coverage1._1.map(t => {
      (t._1.spaceTimeKey.spatialKey, t._2)
    })
    val coverage2tileRDD: RDD[(SpatialKey, MultibandTile)] = coverage2._1.map(t => {
      (t._1.spaceTimeKey.spatialKey, t._2)
    })

    // 投影坐标系、分辨率、范围有一个不一样都要这样处理
    if (coverage1._2.crs != coverage2._2.crs || coverage1._2.cellSize.resolution != coverage2._2.cellSize.resolution || coverage1._2.extent != coverage2._2.extent) {
      var reso: Double = 0.0
      var crs: CRS = null
      var resoRatio: Double = 0.0
      // flag是针对影像分辨率的，flag=1，代表第1张影像分辨率低
      var flag: Int = 0

      // 假如投影不一样，都投影到4326
      if (coverage1._2.crs != coverage2._2.crs) {
        // 这里投影到统一的坐标系下只是为了比较分辨率大小
        val reso1: Double = coverage1._2.cellSize.resolution
        // 把第二张影像投影到第一张影像的坐标系下，然后比较分辨率
        val reso2: Double = tile2._2.reproject(coverage2._2.layout.mapTransform(tile2._1.spaceTimeKey.spatialKey), coverage2._2.crs, coverage1._2.crs).cellSize.resolution
        if (reso1 < reso2) {
          reso = reso1
          resoRatio = reso2 / reso1
          flag = 2
          crs = coverage1._2.crs
        }
        else {
          reso = reso2
          resoRatio = reso1 / reso2
          flag = 1
          crs = coverage2._2.crs
        }
      }
      else {
        crs = coverage1._2.crs
        if (coverage1._2.cellSize.resolution < coverage2._2.cellSize.resolution) {
          reso = coverage1._2.cellSize.resolution
          resoRatio = coverage2._2.cellSize.resolution / coverage1._2.cellSize.resolution
          flag = 2
        }
        else {
          reso = coverage2._2.cellSize.resolution
          resoRatio = coverage1._2.cellSize.resolution / coverage2._2.cellSize.resolution
          flag = 1
        }
      }

      val extent1: Extent = coverage1._2.extent.reproject(coverage1._2.crs, crs)
      val extent2: Extent = coverage2._2.extent.reproject(coverage2._2.crs, crs)

      if (extent1.intersects(extent2)) {
        // 这里一定不会null，但是API要求orNull
        var extent: Extent = extent1.intersection(extent2).orNull

        // 先重投影，重投影到原始范围重投影后的范围、这个范围除以256, 顺势进行裁剪
        val layoutCols: Int = math.max(math.ceil((extent.xmax - extent.xmin) / reso / 256.0).toInt, 1)
        val layoutRows: Int = math.max(math.ceil((extent.ymax - extent.ymin) / reso / 256.0).toInt, 1)
        val tl: TileLayout = TileLayout(layoutCols, layoutRows, 256, 256)
        // Extent必须进行重新计算，因为layoutCols和layoutRows加了黑边，导致范围变化了
        val newExtent: Extent = new Extent(extent.xmin, extent.ymin, extent.xmin + reso * 256.0 * layoutCols, extent.ymin + reso * 256.0 * layoutRows)
        extent = newExtent
        val ld: LayoutDefinition = LayoutDefinition(extent, tl)

        // 这里是coverage1开始进行重投影
        val srcBounds1: Bounds[SpaceTimeKey] = coverage1._2.bounds
        val newBounds1: Bounds[SpatialKey] = Bounds(SpatialKey(srcBounds1.get.minKey.spatialKey._1, srcBounds1.get.minKey.spatialKey._2), SpatialKey(srcBounds1.get.maxKey.spatialKey._1, srcBounds1.get.maxKey.spatialKey._2))
        val rasterMetaData1: TileLayerMetadata[SpatialKey] = TileLayerMetadata(coverage1._2.cellType, coverage1._2.layout, coverage1._2.extent, coverage1._2.crs, newBounds1)
        val coverage1Rdd: RDD[(SpatialKey, MultibandTile)] with Metadata[TileLayerMetadata[SpatialKey]] = MultibandTileLayerRDD(coverage1tileRDD, rasterMetaData1)

        var coverage1tileLayerRdd: RDD[(SpatialKey, MultibandTile)] with Metadata[TileLayerMetadata[SpatialKey]] = coverage1Rdd
        // 如果flag=1， 代表第一张影像的分辨率较低
        if (flag == 1) {
          // 对影像进行瓦片大小重切分
          val srcExtent1: Extent = coverage1._2.layout.extent
          val tileLayout1: TileLayout = coverage1._2.layout.tileLayout
          val tileRatio1: Int = (math.log(resoRatio) / math.log(2)).toInt
          if (tileRatio1 != 0) {
            val newTileSize1: Int = 256 / math.pow(2, tileRatio1).toInt
            val newTileLayout1: TileLayout = TileLayout(tileLayout1.layoutCols * math.pow(2, tileRatio1).toInt, tileLayout1.layoutRows * math.pow(2, tileRatio1).toInt, newTileSize1, newTileSize1)
            val newLayout1: LayoutDefinition = LayoutDefinition(srcExtent1, newTileLayout1)
            val (_, coverage1Retiled) = coverage1Rdd.reproject(coverage1Rdd.metadata.crs, newLayout1)

            val cropExtent1: Extent = extent.reproject(crs, coverage1Retiled.metadata.crs)
            coverage1tileLayerRdd = coverage1Retiled.crop(cropExtent1)
          }
        }

        val (_, reprojectedRdd1): (Int, RDD[(SpatialKey, MultibandTile)] with Metadata[TileLayerMetadata[SpatialKey]]) =
          coverage1tileLayerRdd.reproject(crs, ld)

        // Filter配合extent的强制修改，达到真正裁剪到我们想要的Layout的目的
        val reprojFilter1: RDD[(SpatialKey, MultibandTile)] = reprojectedRdd1.filter(layer => {
          val key: SpatialKey = layer._1
          val extentR: Extent = ld.mapTransform(key)
          extentR.xmin >= extent.xmin && extentR.xmax <= extent.xmax && extentR.ymin >= extent.ymin && extentR.ymax <= extent.ymax
        })

        // metadata需要添加time
        val srcMetadata1: TileLayerMetadata[SpatialKey] = reprojectedRdd1.metadata
        val srcProjBounds1: Bounds[SpatialKey] = srcMetadata1.bounds
        val newProjBounds1: Bounds[SpaceTimeKey] = Bounds(SpaceTimeKey(srcProjBounds1.get.minKey._1, srcProjBounds1.get.minKey._2, time1), SpaceTimeKey(srcProjBounds1.get.maxKey._1, srcProjBounds1.get.maxKey._2, time1))
        val newMetadata1: TileLayerMetadata[SpaceTimeKey] = TileLayerMetadata(srcMetadata1.cellType, srcMetadata1.layout, extent, srcMetadata1.crs, newProjBounds1)

        // 这里LayerMetadata直接使用reprojectRdd1的，尽管SpatialKey有负值也不影响
        val newCoverage1: (RDD[(SpaceTimeBandKey, MultibandTile)], TileLayerMetadata[SpaceTimeKey]) = (reprojFilter1.map(t => {
          (SpaceTimeBandKey(SpaceTimeKey(t._1.col, t._1.row, time1), band1), t._2)
        }), newMetadata1)

        // 这里是coverage2开始进行重投影
        val srcBounds2: Bounds[SpaceTimeKey] = coverage2._2.bounds
        val newBounds2: Bounds[SpatialKey] = Bounds(SpatialKey(srcBounds2.get.minKey.spatialKey._1, srcBounds2.get.minKey.spatialKey._2), SpatialKey(srcBounds2.get.maxKey.spatialKey._1, srcBounds2.get.maxKey.spatialKey._2))
        val rasterMetaData2: TileLayerMetadata[SpatialKey] = TileLayerMetadata(coverage2._2.cellType, coverage2._2.layout, coverage2._2.extent, coverage2._2.crs, newBounds2)
        val coverage2Rdd: RDD[(SpatialKey, MultibandTile)] with Metadata[TileLayerMetadata[SpatialKey]] = MultibandTileLayerRDD(coverage2tileRDD, rasterMetaData2)


        var coverage2tileLayerRdd: RDD[(SpatialKey, MultibandTile)] with Metadata[TileLayerMetadata[SpatialKey]] = coverage2Rdd
        // 如果flag=2， 代表第二张影像的分辨率较低
        if (flag == 2) {
          // 对影像进行瓦片大小重切分
          val srcExtent2: Extent = coverage2._2.layout.extent
          val tileLayout2: TileLayout = coverage2._2.layout.tileLayout
          val tileRatio2: Int = (math.log(resoRatio) / math.log(2)).toInt
          if (tileRatio2 != 0) {
            val newTileSize2: Int = math.max(256 / math.pow(2, tileRatio2).toInt, 1)
            val newTileLayout2: TileLayout = TileLayout(tileLayout2.layoutCols * math.pow(2, tileRatio2).toInt, tileLayout2.layoutRows * math.pow(2, tileRatio2).toInt, newTileSize2, newTileSize2)
            val newLayout2: LayoutDefinition = LayoutDefinition(srcExtent2, newTileLayout2)
            val (_, coverage2Retiled) = coverage2Rdd.reproject(coverage2Rdd.metadata.crs, newLayout2)

            val cropExtent2: Extent = extent.reproject(crs, coverage2Retiled.metadata.crs)
            coverage2tileLayerRdd = coverage2Retiled.crop(cropExtent2)
          }
        }

        val (_, reprojectedRdd2): (Int, RDD[(SpatialKey, MultibandTile)] with Metadata[TileLayerMetadata[SpatialKey]]) =
          coverage2tileLayerRdd.reproject(crs, ld)

        val reprojFilter2: RDD[(SpatialKey, MultibandTile)] = reprojectedRdd2.filter(layer => {
          val key: SpatialKey = layer._1
          val extentR: Extent = ld.mapTransform(key)
          extentR.xmin >= extent.xmin && extentR.xmax <= extent.xmax && extentR.ymin >= extent.ymin && extentR.ymax <= extent.ymax
        })

        // metadata需要添加time
        val srcMetadata2: TileLayerMetadata[SpatialKey] = reprojectedRdd2.metadata
        val srcProjBounds2: Bounds[SpatialKey] = srcMetadata2.bounds
        val newProjBounds2: Bounds[SpaceTimeKey] = Bounds(SpaceTimeKey(srcProjBounds2.get.minKey._1, srcProjBounds2.get.minKey._2, time2), SpaceTimeKey(srcProjBounds2.get.maxKey._1, srcProjBounds2.get.maxKey._2, time2))
        val newMetadata2: TileLayerMetadata[SpaceTimeKey] = TileLayerMetadata(srcMetadata2.cellType, srcMetadata2.layout, extent, srcMetadata2.crs, newProjBounds2)

        // 这里LayerMetadata直接使用reprojectRdd2的，尽管SpatialKey有负值也不影响
        val newCoverage2: (RDD[(SpaceTimeBandKey, MultibandTile)], TileLayerMetadata[SpaceTimeKey]) = (reprojFilter2.map(t => {
          (SpaceTimeBandKey(SpaceTimeKey(t._1.col, t._1.row, time2), band2), t._2)
        }), newMetadata2)


        (newCoverage1, newCoverage2)
      }
      else {
        (coverage1, coverage2)
      }
    }
    else {
      (coverage1, coverage2)
    }
  }

  // TODO lrx: ！！！这里要注意一下数据类型！！！
  // Geotrellis的MultibandTile必须要求数据类型一致
  // TODO lrx: 检查TileLayerMetadata的CellType会不会影像原来的TileLayerRDD重投影后的数据类型
  def coverageTemplate(coverage1: (RDD[(SpaceTimeBandKey, MultibandTile)], TileLayerMetadata[SpaceTimeKey]), coverage2: (RDD[(SpaceTimeBandKey, MultibandTile)], TileLayerMetadata[SpaceTimeKey]), func: (Tile, Tile) => Tile): (RDD[(SpaceTimeBandKey, MultibandTile)], TileLayerMetadata[SpaceTimeKey]) = {

    def funcMulti(multibandTile1: MultibandTile, multibandTile2: MultibandTile): MultibandTile = {
      val bands1: Vector[Tile] = multibandTile1.bands
      val bands2: Vector[Tile] = multibandTile2.bands
      val bandsFunc: mutable.ArrayBuffer[Tile] = mutable.ArrayBuffer.empty[Tile]
      for (i <- bands1.indices) {
        bandsFunc.append(func(bands1(i), bands2(i)))
      }
      MultibandTile(bandsFunc)
    }

    var cellType: CellType = null
    val resampleTime: Long = Instant.now.getEpochSecond
    val (coverage1Reprojected, coverage2Reprojected) = checkProjResoExtent(coverage1, coverage2)
    val bandList1: mutable.ListBuffer[String] = coverage1Reprojected._1.first()._1.measurementName
    val bandList2: mutable.ListBuffer[String] = coverage2Reprojected._1.first()._1.measurementName
    if (bandList1.length == bandList2.length) {
      val coverage1NoTime: RDD[(SpatialKey, MultibandTile)] = coverage1Reprojected._1.map(t => (t._1.spaceTimeKey.spatialKey, t._2))
      val coverage2NoTime: RDD[(SpatialKey, MultibandTile)] = coverage2Reprojected._1.map(t => (t._1.spaceTimeKey.spatialKey, t._2))
      val rdd: RDD[(SpatialKey, (MultibandTile, MultibandTile))] = coverage1NoTime.join(coverage2NoTime)
      if (bandList1.diff(bandList2).isEmpty && bandList2.diff(bandList1).isEmpty) {
        val indexMap: Map[String, Int] = bandList2.zipWithIndex.toMap
        val indices: mutable.ListBuffer[Int] = bandList1.map(indexMap)
        (rdd.map(t => {
          val bands1: Vector[Tile] = t._2._1.bands
          val bands2: Vector[Tile] = t._2._2.bands
          cellType = func(bands1.head, bands2(indices.head)).cellType
          val bandsFunc: mutable.ArrayBuffer[Tile] = mutable.ArrayBuffer.empty[Tile]
          for (i <- bands1.indices) {
            bandsFunc.append(func(bands1(i), bands2(indices(i))))
          }
          (SpaceTimeBandKey(SpaceTimeKey(t._1.col, t._1.row, resampleTime), bandList1), MultibandTile(bandsFunc))
        }), TileLayerMetadata(cellType, coverage1Reprojected._2.layout, coverage1Reprojected._2.extent, coverage1Reprojected._2.crs, coverage1Reprojected._2.bounds))
      }
      else {
        cellType = funcMulti(coverage1Reprojected._1.first()._2, coverage2Reprojected._1.first()._2).cellType
        (rdd.map(t => {
          (SpaceTimeBandKey(SpaceTimeKey(t._1.col, t._1.row, resampleTime), bandList1), funcMulti(t._2._1, t._2._2))
        }), TileLayerMetadata(cellType, coverage1Reprojected._2.layout, coverage1Reprojected._2.extent, coverage1Reprojected._2.crs, coverage1Reprojected._2.bounds))
      }
    }
    else {
      throw new IllegalArgumentException("Error: 波段数量不相等")
    }
  }

  def coverageTemplate(coverage: (RDD[(SpaceTimeBandKey, MultibandTile)], TileLayerMetadata[SpaceTimeKey]), func: Tile => Tile): (RDD[(SpaceTimeBandKey, MultibandTile)], TileLayerMetadata[SpaceTimeKey]) = {

    def funcMulti(multibandTile: MultibandTile): MultibandTile = {
      val bands: Vector[Tile] = multibandTile.bands
      val bandsFunc: mutable.ArrayBuffer[Tile] = mutable.ArrayBuffer.empty[Tile]
      for (i <- bands.indices) {
        bandsFunc.append(func(bands(i)))
      }
      MultibandTile(bandsFunc)
    }

    val cellType: CellType = coverage._1.first()._2.cellType

    (coverage._1.map(t => (t._1, funcMulti(t._2))), TileLayerMetadata(cellType, coverage._2.layout, coverage._2.extent, coverage._2.crs, coverage._2.bounds))
  }

  def focalMethods(coverage: (RDD[(SpaceTimeBandKey, MultibandTile)], TileLayerMetadata[SpaceTimeKey]), kernelType: String,
                   focalFunc: (Tile, Neighborhood, Option[GridBounds[Int]], TargetCell) => Tile, radius: Int): (RDD[
    (SpaceTimeBandKey, MultibandTile)], TileLayerMetadata[SpaceTimeKey]) = {
    kernelType match {
      case "square" => {
        val neighborhood = focal.Square(radius)
        val coverageRdd: RDD[(SpaceTimeBandKey, MultibandTile)] = (coverage._1.map(t => {
          (t._1, MultibandTile(t._2.bands.map(b => {
            focalFunc(b, neighborhood, None, focal.TargetCell.All)
          })))
        }))
        (coverageRdd, coverage._2)
      }
      case "circle" => {
        val neighborhood = focal.Circle(radius)
        val coverageRdd: RDD[(SpaceTimeBandKey, MultibandTile)] = (coverage._1.map(t => {
          (t._1, MultibandTile(t._2.bands.map(b => {
            focal.Max(b, neighborhood, None, focal.TargetCell.All)
          })))
        }))
        (coverageRdd, coverage._2)
      }
    }
  }
}
