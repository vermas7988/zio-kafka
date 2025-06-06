package zio.kafka.serde

import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.serialization.{ Serde => KafkaSerde }
import zio._

import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * A serializer and deserializer for values of type T
 *
 * @tparam R
 *   Environment available to the deserializer
 * @tparam T
 *   Value type
 */
trait Serde[-R, T] extends Deserializer[R, T] with Serializer[R, T] {

  /**
   * Creates a new Serde that uses optional values. Null data will be mapped to None values.
   */
  override def asOption: Serde[R, Option[T]] =
    Serde(super[Deserializer].asOption)(super[Serializer].asOption)

  /**
   * Creates a new Serde that executes its serialization and deserialization functions on the blocking threadpool.
   */
  override def blocking: Serde[R, T] =
    Serde(super[Deserializer].blocking)(super[Serializer].blocking)

  /**
   * Converts to a Serde of type U with pure transformations
   */
  def inmap[U](f: T => U)(g: U => T): Serde[R, U] =
    Serde(map(f))(contramap(g))

  /**
   * Convert to a Serde of type U with effectful transformations
   */
  def inmapZIO[R1 <: R, U](f: T => RIO[R1, U])(g: U => RIO[R1, T]): Serde[R1, U] =
    Serde(mapZIO(f))(contramapZIO(g))
}

object Serde extends Serdes {

  /**
   * Create a Serde from a deserializer and serializer function
   *
   * The (de)serializer functions can returned a failure ZIO with a Throwable to indicate (de)serialization failure
   */
  def apply[R, T](
    deser: (String, Headers, Array[Byte]) => RIO[R, T]
  )(ser: (String, Headers, T) => RIO[R, Array[Byte]]): Serde[R, T] =
    new Serde[R, T] {
      override final def serialize(topic: String, headers: Headers, value: T): RIO[R, Array[Byte]] =
        ser(topic, headers, value)
      override final def deserialize(topic: String, headers: Headers, data: Array[Byte]): RIO[R, T] =
        deser(topic, headers, data)
    }

  /**
   * Create a Serde from a deserializer and serializer function
   */
  def apply[R, T](deser: Deserializer[R, T])(ser: Serializer[R, T]): Serde[R, T] =
    new Serde[R, T] {
      override final def serialize(topic: String, headers: Headers, value: T): RIO[R, Array[Byte]] =
        ser.serialize(topic, headers, value)
      override final def deserialize(topic: String, headers: Headers, data: Array[Byte]): RIO[R, T] =
        deser.deserialize(topic, headers, data)
    }

  /**
   * Create a Serde from a Kafka Serde
   */
  def fromKafkaSerde[T](serde: KafkaSerde[T], props: Map[String, AnyRef], isKey: Boolean): Task[Serde[Any, T]] =
    ZIO
      .attempt(serde.configure(props.asJava, isKey))
      .as(
        new Serde[Any, T] {
          private final val serializer   = serde.serializer()
          private final val deserializer = serde.deserializer()

          override final def deserialize(topic: String, headers: Headers, data: Array[Byte]): Task[T] =
            ZIO.attempt(deserializer.deserialize(topic, headers, data))

          override final def serialize(topic: String, headers: Headers, value: T): Task[Array[Byte]] =
            ZIO.attempt(serializer.serialize(topic, headers, value))
        }
      )

  implicit def deserializerWithError[R, T](implicit deser: Deserializer[R, T]): Deserializer[R, Try[T]] =
    deser.asTry
}
