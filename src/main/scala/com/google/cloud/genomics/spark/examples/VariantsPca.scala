/*
Copyright 2014 Google Inc. All rights reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.google.cloud.genomics.spark.examples

import collection.JavaConversions._

import org.apache.log4j.Level
import org.apache.log4j.Logger
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.linalg.distributed.RowMatrix
import org.apache.spark.rdd.RDD

import com.google.cloud.genomics.spark.examples.rdd.FixedContigSplits
import com.google.cloud.genomics.spark.examples.rdd.VariantCalls
import com.google.cloud.genomics.spark.examples.rdd.VariantCallsRDD
import com.google.cloud.genomics.spark.examples.rdd.VariantKey
import com.google.cloud.genomics.spark.examples.rdd.VariantsPartitioner


/** 
 * Saves the result of a variant search as an RDD of VariantCalls
 */
object VariantsSource {
  def main(args: Array[String]) = {
    val conf = new GenomicsConf(args)
    val sc = conf.newSparkContext(this.getClass.getName)
    Logger.getLogger("org").setLevel(Level.WARN)
    val contigs = conf.getContigs
    val data = new VariantCallsRDD(sc,
      this.getClass.getName,
      conf.clientSecrets(),
      VariantDatasets.Google_1000_genomes_phase_1,
      new VariantsPartitioner(contigs, 
          FixedContigSplits(conf.partitionsPerContig())),
          conf.maxResults())
      .saveAsObjectFile(conf.outputPath())
  }
}

object VariantsPcaDriver {
  def main(args: Array[String]) = {
    val conf = new PcaConf(args)
    val sc = conf.newSparkContext(this.getClass.getName)
    Logger.getLogger("org").setLevel(Level.WARN)
    val out = conf.outputPath()
    if (!conf.nocomputeSimilarity()) {
      val data = if (conf.inputPath.isDefined) {
        sc.objectFile[(VariantKey, VariantCalls)](conf.inputPath())
      } else {
        val contigs = conf.getContigs
        new VariantCallsRDD(sc,
          this.getClass.getName,
          conf.clientSecrets(),
          VariantDatasets.Google_1000_genomes_phase_1,
          new VariantsPartitioner(contigs, FixedContigSplits(1)))
      }
      
      val logger = Logger.getLogger(this.getClass.getName)
      val samplesWithVariant = data.map(kv => kv._2)
        .map(_.calls.getOrElse(Seq()))
        .map(calls => calls.filter(_.genotype.foldLeft(false)(_ || _ > 0)))
        // Keep only the variants that have more than 1 call
        .filter(_.size > 1).cache
        
      val callsets = samplesWithVariant.map(_.map(_.callsetName))
      val indexes = callsets.flatMap(callset => callset)
        .distinct(conf.reducePartitions()).zipWithIndex()
      indexes.saveAsObjectFile(s"${out}-indexes.dat")
      val names = indexes.collectAsMap
      val broadcastNames = sc.broadcast(names)
      println(s"Distinct calls ${names.size}") // #rows
      val toIds = callsets.map(callset => {
        val mapping = broadcastNames.value
        callset.map(mapping(_).toInt)
      }).cache()
      computeSimilarity(toIds, out, conf)
    }
    doPca(sc, conf, out)
    sc.stop
  }

  def computeSimilarity(toIds: RDD[Seq[Int]], out: String, conf: GenomicsConf) {
    // Keep track of how many calls shared the same variant
    val similar = toIds.flatMap(callset => 
      // Emit only half of the counts
      for (c1 <- callset.iterator; c2 <- callset.iterator if c1 <= c2)
        yield ((c1, c2), 1)
    ).reduceByKey(_ + _, conf.reducePartitions()).cache()
    similar.saveAsObjectFile(s"${out}-similar.dat")
  }

  def doPca(sc: SparkContext, conf: GenomicsConf, out: String) {
    val similarFromFile =
      sc.objectFile[((Int, Int), Int)](s"${out}-similar.dat")
    val indexes =
      sc.objectFile[(String, Long)](s"${out}-indexes.dat")
      .map(item => (item._2, item._1)).collectAsMap
    val rowCount = indexes.size()
    val entries =
      similarFromFile.map(item => (item._1._1, item._1._2, item._2.toDouble))
        .flatMap(item => {
          // Rebuild the symmetric matrix
           if (item._1 < item._2) {
             Seq(item, (item._2, item._1, item._3))
           } else {
             Seq(item)
           }
         })
        .map(item => (item._1, (item._2, item._3)))
        .groupByKey()
        .sortByKey(true)
        .cache
    val rowSums = entries.map(_._2.foldLeft(0D)(_ + _._2)).collect
    val broadcastRowSums = sc.broadcast(rowSums)
    val matrixSum = rowSums.reduce(_ + _)
    val matrixMean = matrixSum / rowCount / rowCount;
    val centeredRows = entries.map(indexedRow => {
        val localRowSums = broadcastRowSums.value
        val i = indexedRow._1
        val row = indexedRow._2
        val rowMean = localRowSums(i) / rowCount;
        row.map(entry => {
          val j = entry._1
          val data = entry._2
          val colMean = localRowSums(j) / rowCount;
          (j, data - rowMean - colMean + matrixMean)
        }).toSeq
    })
    val rows = centeredRows.map(row => Vectors.sparse(rowCount, row))
    val matrix = new RowMatrix(rows)
    val pca = matrix.computePrincipalComponents(conf.numPc())
    val array = pca.toArray
    val table = for (i <- 0 until pca.numRows) 
      yield (indexes(i), array(i), array(i + pca.numRows))
    
    table.sortBy(_._1).foreach(tuple => 
        println(s"${tuple._1}\t\t${tuple._2}\t${tuple._3}"))
  }
}