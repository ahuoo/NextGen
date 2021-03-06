package com.ahuoo.nextetl.graphx

import java.time.format.DateTimeFormatter
import java.time.LocalDateTime
import java.util.Calendar
import java.util.concurrent.Executors

import com.ahuoo.nextetl.BaseApp
import com.typesafe.config.{Config, ConfigFactory, ConfigObject}
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
import scala.util.{Failure, Random, Success}
import org.apache.log4j.Logger
import org.apache.spark.sql.SparkSession
import com.typesafe.config.{Config, ConfigFactory, ConfigObject}
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}
import org.apache.spark.graphx._

import scala.util.hashing.MurmurHash3
import scala.util.Properties
import org.apache.spark.rdd.RDD

object GraphxTest  extends  BaseApp {

  def run(): Unit = {
    import spark.implicits._
    val sc = spark.sparkContext

    println("Spark Version: " + spark.version)
    println("Scala Version: " + Properties.versionNumberString)
    println("Java Version: " + System.getProperty("java.version"))
    log.info("Hello demo")



    // Test with some sample data
    val empData = Array(
        ("EMP001", "Bob", "Baker", "CEO", null.asInstanceOf[String])
      , ("EMP002", "Jim", "Lake", "CIO", "EMP001")
   //   , ("EMP003", "Tim", "Gorab", "MGR", "EMP002")
/*      , ("EMP004", "Rick", "Summer", "MGR", "EMP002")
      , ("EMP005", "Sam", "Cap", "Lead", "EMP004")
      , ("EMP006", "Ron", "Hubb", "Sr.Dev", "EMP005")
      , ("EMP007", "Cathy", "Watson", "Dev", "EMP006")
      , ("EMP008", "Samantha", "Lion", "Dev", "EMP007")
      , ("EMP009", "Jimmy", "Copper", "Dev", "EMP007")
      , ("EMP010", "Shon", "Taylor", "Intern", "EMP009")*/
//      , ("EMP011", "Tiger", "Tsai", "Intern", null.asInstanceOf[String])
//      , ("EMP012", "Tom", "Tsai", "Intern", "EMP011")

    )
    // create dataframe with some partitions
    val empDF = sc.parallelize(empData, 3).toDF("emp_id","first_name","last_name","title","mgr_id").cache()
    // primary key , root, path - dataframe to graphx for vertices
    val empVertexDF = empDF.selectExpr("emp_id","concat(first_name,' ',last_name)","concat(last_name,' ',first_name)")
    empVertexDF.show
    // parent to child - dataframe to graphx for edges
    val empEdgeDF = empDF.selectExpr("mgr_id","emp_id").filter("mgr_id is not null")

    empEdgeDF.show
    // call the function
    val empHirearchyExtDF = calcTopLevelHierarcy(empVertexDF,empEdgeDF)
      .map{ case(pk,(level,root,path,iscyclic,isleaf)) => (pk.asInstanceOf[String],level,root.asInstanceOf[String],path,iscyclic,isleaf)}
      .toDF("emp_id_pk","level","root","path","iscyclic","isleaf").cache()
    // extend original table with new columns
    val empHirearchyDF = empHirearchyExtDF.join(empDF , empDF.col("emp_id") === empHirearchyExtDF.col("emp_id_pk")).selectExpr("emp_id","first_name","last_name","title","mgr_id","level","root","path","iscyclic","isleaf")
    // print
    empHirearchyDF.orderBy("emp_id").show(false)
  }

  // The code below demonstrates use of Graphx Pregel API - Scala 2.11+
  // functions to build the top down hierarchy
  //setup & call the pregel api
  def calcTopLevelHierarcy(vertexDF: DataFrame, edgeDF: DataFrame): RDD[(Any,(Int,Any,String,Int,Int))] = {
    // create the vertex RDD
    // primary key, root, path
    val verticesRDD = vertexDF
      .rdd
      .map{x=> (x.get(0),x.get(1) , x.get(2))}
      .map{ x => (MurmurHash3.stringHash(x._1.toString).toLong, ( x._1.asInstanceOf[Any], x._2.asInstanceOf[Any] , x._3.asInstanceOf[String]) ) }
    // create the edge RDD
    // top down relationship
    val EdgesRDD = edgeDF.rdd.map{x=> (x.get(0),x.get(1))}
      .map{ x => Edge(MurmurHash3.stringHash(x._1.toString).toLong,MurmurHash3.stringHash(x._2.toString).toLong,"topdown" )}
    // create graph
    val graph = Graph(verticesRDD, EdgesRDD).cache()
    val pathSeperator = """/"""
    // initialize id,level,root,path,iscyclic, isleaf
    val initialMsg = (0L,0,0.asInstanceOf[Any],List("dummy"),0,1)
    // add more dummy attributes to the vertices - id, level, root, path, isCyclic, existing value of current vertex to build path, isleaf, pk
    val initialGraph = graph.mapVertices((id, v) => (id,0,v._2,List(v._3),0,v._3,1,v._1) )
    val hrchyRDD = initialGraph.pregel(initialMsg,
      Int.MaxValue,
      EdgeDirection.Out)(
      setMsg,
      sendMsg,
      mergeMsg)
    // build the path from the list
    val hrchyOutRDD = hrchyRDD.vertices.map{case(id,v) => (v._8,(v._2,v._3,pathSeperator + v._4.reverse.mkString(pathSeperator),v._5, v._7 )) }
    hrchyOutRDD
  }
  //mutate the value of the vertices
  def setMsg(vertexId: VertexId, value: (Long,Int,Any,List[String], Int,String,Int,Any), message: (Long,Int, Any,List[String],Int,Int)): (Long,Int, Any,List[String],Int,String,Int,Any) = {
    println("-----------------------------")
    println("setMsg: Message:" + message) //(871779906,1,Jim Lake,List(Lake Jim),0,1)
    println(s"setMsg: vertexId: ${vertexId}  Value:" + value)
    if (message._2 < 1) { //superstep 0 - initialize
      //println((value._1,value._2+1,value._3,value._4,value._5,value._6,value._7,value._8))
      (value._1,value._2+1,value._3,value._4,value._5,value._6,value._7,value._8)
    } else if ( message._5 == 1) { // set isCyclic
      println("-->change isCyclic=1" + (value._1, value._2, value._3, value._4, message._5, value._6, value._7,value._8))
      (value._1, value._2, value._3, value._4, message._5, value._6, value._7,value._8)
    } else if ( message._6 == 0 ) { // set isleaf
      println("-->change isleaf=0" +(value._1, value._2, value._3, value._4, value._5, value._6, message._6,value._8))
      (value._1, value._2, value._3, value._4, value._5, value._6, message._6,value._8)
    } else { // set new values
      println("-->set new value" +( message._1,value._2+1, message._3, value._6 :: message._4 , value._5,value._6,value._7,value._8))
      ( message._1,value._2+1, message._3, value._6 :: message._4 , value._5,value._6,value._7,value._8)
    }
  }
  // send the value to vertices
  def sendMsg(triplet: EdgeTriplet[(Long,Int,Any,List[String],Int,String,Int,Any), _]): Iterator[(VertexId, (Long,Int,Any,List[String],Int,Int))] = {
    val sourceVertex = triplet.srcAttr
    val destinationVertex = triplet.dstAttr
    // check for icyclic
    if (sourceVertex._1 == triplet.dstId || sourceVertex._1 == destinationVertex._1)
      if (destinationVertex._5==0) { //set iscyclic
        Iterator((triplet.dstId, (sourceVertex._1, sourceVertex._2, sourceVertex._3,sourceVertex._4, 1,sourceVertex._7)))
      } else {
        Iterator.empty
      }
    else {
      if (sourceVertex._7==1) //is NOT leaf
      {
        println("sendMsg: sourceVertex._7==1 " + (triplet.srcId, (sourceVertex._1,sourceVertex._2,sourceVertex._3, sourceVertex._4 ,0, 0 )))
        Iterator((triplet.srcId, (sourceVertex._1,sourceVertex._2,sourceVertex._3, sourceVertex._4 ,0, 0 )))
      }
      else { // set new values
        println("sendMsg: sourceVertex._7<>1 " + (triplet.dstId, (sourceVertex._1, sourceVertex._2, sourceVertex._3, sourceVertex._4, 0, 1)))
        Iterator((triplet.dstId, (sourceVertex._1, sourceVertex._2, sourceVertex._3, sourceVertex._4, 0, 1)))
      }
    }
  }
  // receive the values from all connected vertices
  def mergeMsg(msg1: (Long,Int,Any,List[String],Int,Int), msg2: (Long,Int, Any,List[String],Int,Int)): (Long,Int,Any,List[String],Int,Int) = {
    // dummy logic not applicable to the data in this usecase
    msg2
  }
}
