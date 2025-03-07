package zio.kafka

import org.apache.kafka.clients.consumer.{ ConsumerConfig, KafkaConsumer }
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import zio.{ System => _, _ }
import zio.stream._
import zio.kafka.producer._
import zio.kafka.serde._
import zio.kafka.consumer.Consumer.{ AutoOffsetStrategy, OffsetRetrieval }

import scala.jdk.CollectionConverters._
import java.time.Duration

import java.util.concurrent.TimeUnit

object PopulateTopic extends ZIOAppDefault {
  def dataStream(length: Long): ZStream[Any, Nothing, (String, String)] =
    ZStream
      .repeatZIO(Random.nextString(16) <*> Random.nextString(128))
      .take(length)
      .rechunk(500)

  override def run: ZIO[Any, Throwable, Unit] =
    ZIO.scoped {
      for {
        producer <- Producer.make(
                      ProducerSettings(List("localhost:9092"))
                        .withProperty(ProducerConfig.ACKS_CONFIG, "1")
                        .withProperty(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4")
                    )
        _ <- dataStream(872000).map { case (k, v) =>
               new ProducerRecord("inputs-topic", null, null, k, v)
             }.mapChunksZIO { chunk =>
               producer
                 .produceChunkAsync[Any, String, String](chunk, Serde.string, Serde.string)
                 .map(Chunk(_))
             }
               .mapZIOPar(5) { produceTasks =>
                 produceTasks.flatMap(chunk => Console.printLine(s"Wrote chunk of ${chunk.size}"))
               }
               .runDrain
      } yield ()
    }
}

object Plain {
  def main(args: Array[String]): Unit = {
    val props = new java.util.Properties
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
    props.put(ConsumerConfig.GROUP_ID_CONFIG, s"plain-${scala.util.Random.nextInt()}")
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

    val consumer = new KafkaConsumer[String, String](props, new StringDeserializer, new StringDeserializer)

    consumer.subscribe(List("inputs-topic").asJava)

    var messageCounter = 0
    var lengthCounter  = 0
    val startTime      = System.currentTimeMillis()
    val pollDuration   = Duration.ofMillis(50)

    while (messageCounter < 1000000) {
      val batch = consumer.poll(pollDuration)
      println(s"Got batch of ${batch.count()}")

      batch.iterator().asScala.foreach { record =>
        messageCounter += 1
        lengthCounter += record.value().length
      }

      println(s"messageCounter = $messageCounter")
    }

    val duration = System.currentTimeMillis() - startTime
    println(
      s"Done in $duration ms; rate = ${(messageCounter / duration) * 1000} messages/s or ${((messageCounter * 144) / duration) * 1000} bytes/s"
    )

    consumer.close()
  }
}

object ZIOKafka extends ZIOAppDefault {
  import zio.kafka.consumer._

  override def run: ZIO[Any with ZIOAppArgs with Scope, Throwable, Unit] = {
    val expectedCount = 1000000
    val settings = ConsumerSettings(List("localhost:9092"))
      .withGroupId(s"zio-kafka-${scala.util.Random.nextInt()}")
      .withOffsetRetrieval(OffsetRetrieval.Auto(AutoOffsetStrategy.Earliest))
      .withProperty("fetch.min.bytes", "128000")
      .withPollTimeout(50.millis)

    for {
      _         <- Console.readLine
      startTime <- Clock.currentTime(TimeUnit.MILLISECONDS)
      consumer  <- Consumer.make(settings)
      _ <- consumer
             .plainStream(Subscription.topics("inputs-topic"), Serde.string, Serde.string)
             .take(expectedCount.toLong)
             .mapChunks { recordChunk =>
               val messageCount = recordChunk.size
               println(s"Got chunk of $messageCount")
               val lengthCount = recordChunk.foldLeft(0)(_ + _.value.length)

               Chunk(messageCount -> lengthCount)
             }
             .runDrain
      endTime <- Clock.currentTime(TimeUnit.MILLISECONDS)
      duration = endTime - startTime
      _ <-
        Console.printLine(
          s"Done in $duration ms; rate = ${(expectedCount / duration) * 1000} messages/s or ${((expectedCount * 144) / duration) * 1000} bytes/s"
        )
    } yield ()
  }
}
