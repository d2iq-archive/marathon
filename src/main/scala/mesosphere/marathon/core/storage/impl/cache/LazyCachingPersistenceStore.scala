package mesosphere.marathon.core.storage.impl.cache

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.{ Keep, Sink, Source }
import akka.{ Done, NotUsed }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.storage.impl.BasePersistenceStore
import mesosphere.marathon.core.storage.{ IdResolver, PersistenceStore }
import mesosphere.util.LockManager

import scala.async.Async.{ async, await }
import scala.collection.concurrent.TrieMap
import scala.collection.immutable.Seq
import scala.concurrent.{ ExecutionContext, Future }

/**
  * A Write Ahead Cache of another persistence store that lazily loads values into the cache.
  *
  * @param store The store to cache
  * @param mat a materializer for Akka Streaming
  * @param ctx The execution context for future chaining.
  * @tparam K The persistence store's primary key type
  * @tparam Serialized The serialized format for the persistence store.
  */
class LazyCachingPersistenceStore[K, Category, Serialized](
    store: BasePersistenceStore[K, Category, Serialized])(implicit
  mat: Materializer,
    ctx: ExecutionContext) extends PersistenceStore[K, Category, Serialized] with StrictLogging {

  val lockManager = LockManager.create()
  private[storage] val idCache = TrieMap.empty[Category, Seq[Any]]
  private[storage] val valueCache = TrieMap.empty[K, Option[Any]]

  override def ids[Id, V]()(implicit ir: IdResolver[Id, V, Category, K]): Source[Id, NotUsed] = {
    val category = ir.category
    val idsFuture = lockManager.executeSequentially(category.toString) {
      if (idCache.contains(category)) {
        Future.successful(idCache(category).asInstanceOf[Seq[Id]])
      } else {
        async {
          val children = await(store.ids.toMat(Sink.seq)(Keep.right).run())
          idCache(category) = children
          children
        }
      }
    }
    Source.fromFuture(idsFuture).mapConcat(identity)
  }

  override def deleteAll[Id, V](k: Id)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] = {
    val category = ir.category
    val storageId = ir.toStorageId(k, None)
    lockManager.executeSequentially(ir.category.toString) {
      lockManager.executeSequentially(storageId.toString) {
        async {
          await(store.deleteAll(k))
          valueCache.remove(storageId)
          val old = idCache.getOrElse(category, Nil)
          val children = old.filter(_ != storageId)
          if (children.nonEmpty) {
            idCache.put(category, children)
          } else {
            idCache.remove(category)
          }
          Done
        }
      }
    }
  }

  override def get[Id, V](id: Id)(implicit
    ir: IdResolver[Id, V, Category, K],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] = {
    val storageId = ir.toStorageId(id, None)
    lockManager.executeSequentially(storageId.toString) {
      val cached = valueCache.get(storageId)
      cached match {
        case Some(v) =>
          Future.successful(v.asInstanceOf[Option[V]])
        case None =>
          async {
            val value = await(store.get(id))
            valueCache.put(storageId, value)
            value
          }
      }
    }
  }

  override def get[Id, V](id: Id, version: OffsetDateTime)(implicit
    ir: IdResolver[Id, V, Category, K],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] =
    store.get(id, version)

  override def store[Id, V](id: Id, v: V)(implicit
    ir: IdResolver[Id, V, Category, K],
    m: Marshaller[V, Serialized]): Future[Done] = {
    val category = ir.category
    val storageId = ir.toStorageId(id, None)
    lockManager.executeSequentially(category.toString) {
      lockManager.executeSequentially(storageId.toString) {
        async {
          await(store.store(id, v))
          valueCache.put(storageId, Some(v))
          val cachedIds = idCache.getOrElse(category, Nil)
          idCache.put(category, id +: cachedIds)
          Done
        }
      }
    }
  }

  override def store[Id, V](id: Id, v: V, version: OffsetDateTime)(implicit
    ir: IdResolver[Id, V, Category, K],
    m: Marshaller[V, Serialized]): Future[Done] = {
    val category = ir.category
    val storageId = ir.toStorageId(id, None)
    lockManager.executeSequentially(category.toString) {
      lockManager.executeSequentially(storageId.toString) {
        async {
          await(store.store(id, v, version))
          valueCache.putIfAbsent(storageId, Some(v))
          val old = idCache.getOrElse(category, Nil)
          idCache.put(category, id +: old)
          Done
        }
      }
    }
  }

  override def versions[Id, V](id: Id)(implicit ir: IdResolver[Id, V, Category, K]): Source[OffsetDateTime, NotUsed] =
    store.versions(id)

  override def delete[Id, V](
    k: Id,
    version: OffsetDateTime)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] =
    store.delete(k, version)

  override def toString: String = s"LazyCachingPersistenceStore($store)"
}
