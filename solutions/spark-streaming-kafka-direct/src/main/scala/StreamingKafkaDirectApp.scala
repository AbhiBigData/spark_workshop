import java.util.concurrent.{ExecutorService, Executors}

import org.apache.log4j.{Level, LogManager}
import org.apache.spark.sql.SparkSession
import org.apache.spark.streaming.{Seconds, StreamingContext}

object StreamingKafkaDirectApp extends App {

  LogManager.getRootLogger.setLevel(Level.INFO)

  val spark = SparkSession.builder
    .config("spark.sql.warehouse.dir", "target/spark-warehouse")
    .getOrCreate
  val sc = spark.sparkContext
  val ssc = new StreamingContext(sc, batchDuration = Seconds(10))
  try {
    import org.apache.spark.streaming.kafka010._

    val preferredHosts = LocationStrategies.PreferConsistent
    val topics = List("topic1", "topic2", "topic3")
    import org.apache.kafka.common.serialization.StringDeserializer
    val kafkaParams = Map(
      "bootstrap.servers" -> "localhost:9092",
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> "StreamingKafkaDirectApp",
      "auto.offset.reset" -> "earliest"
    )
    import org.apache.kafka.common.TopicPartition
    // val offsets = Map(new TopicPartition("topic3", 0) -> 2L)
    val offsets = Map.empty[TopicPartition, Long]

    val dstream = KafkaUtils.createDirectStream[String, String](
      ssc,
      preferredHosts,
      ConsumerStrategies.Subscribe[String, String](topics, kafkaParams, offsets))

    import org.apache.spark.storage.StorageLevel.MEMORY_AND_DISK_SER
    dstream.persist(MEMORY_AND_DISK_SER)

    def reduceFunc(v1: String, v2: String) = s"$v1 + $v2"
    // Pipeline #1
    dstream.map { r =>
      println(s"value: ${r.value}")
      // FIXME What if there is one or zero messages on input?
      val Array(key, value, _*) = r.value.split("\\s+") // only two elements accepted
      println(s">>> key = $key")
      println(s">>> value = $value")
      (key, value)
    }.reduceByKeyAndWindow(
      reduceFunc, windowDuration = Seconds(30), slideDuration = Seconds(10))
      .print()

    // Pipeline #2
    dstream.foreachRDD { rdd =>
      // Get the offset ranges in the RDD
      val offsetRanges = rdd.asInstanceOf[HasOffsetRanges].offsetRanges
      for (o <- offsetRanges) {
        println(s">>> ${o.topic} ${o.partition} offsets: ${o.fromOffset} to ${o.untilOffset}")
      }
    }

    // Pipeline #3
    // Spark SQL integration
    // See http://spark.apache.org/docs/latest/streaming-programming-guide.html#dataframe-and-sql-operations
    val executorService = Executors.newFixedThreadPool(1)
    dstream.map(cr => cr.value).foreachRDD { rdd =>
      import org.apache.spark.sql._
      val spark = SparkSession.builder.config(rdd.sparkContext.getConf).getOrCreate
      import spark.implicits._
      import spark.sql

      val records = rdd.toDF("record")
      records.createOrReplaceTempView("records")

      executorService.submit {
        new Runnable {
          override def run(): Unit = {
            sql("select * from records").show(truncate = false)
          }
        }
      }
    }

    ssc.start
    ssc.awaitTermination()
  } finally {
    ssc.stop()
  }
}
