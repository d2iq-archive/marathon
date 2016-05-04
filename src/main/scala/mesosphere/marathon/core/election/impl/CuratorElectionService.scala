package mesosphere.marathon.core.election.impl

import akka.actor.ActorSystem
import akka.event.EventStream
import com.codahale.metrics.MetricRegistry
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.base.ShutdownHooks
import mesosphere.marathon.metrics.Metrics
import org.apache.curator.{ RetrySleeper, RetryPolicy }
import org.apache.curator.framework.{ CuratorFramework, CuratorFrameworkFactory }
import org.apache.curator.framework.recipes.leader.{ LeaderLatch, LeaderLatchListener }
import org.apache.zookeeper.{ ZooDefs, KeeperException, CreateMode }
import org.slf4j.LoggerFactory

import scala.concurrent.{ Await, Future }
import scala.util.control.NonFatal
import scala.concurrent.duration._

class CuratorElectionService(
  config: MarathonConf,
  system: ActorSystem,
  eventStream: EventStream,
  http: HttpConf,
  metrics: Metrics = new Metrics(new MetricRegistry),
  hostPort: String,
  backoff: ExponentialBackoff,
  shutdownHooks: ShutdownHooks) extends ElectionServiceBase(
  config, system, eventStream, metrics, backoff, shutdownHooks
) {
  private lazy val log = LoggerFactory.getLogger(getClass.getName)

  private lazy val client = provideCuratorClient()
  private var maybeLatch: Option[LeaderLatch] = None

  override def leaderHostPortImpl: Option[String] = synchronized {
    try {
      maybeLatch.flatMap { l =>
        val participant = l.getLeader
        if (participant.isLeader) Some(participant.getId) else None
      }
    }
    catch {
      case NonFatal(e) =>
        log.error("error while getting current leader", e)
        None
    }
  }

  override def offerLeadershipImpl(): Unit = synchronized {
    log.info("Using HA and therefore offering leadership")
    maybeLatch match {
      case Some(l) =>
        log.info("Offering leadership while being candidate")
        l.close()
      case _ =>
    }
    maybeLatch = Some(new LeaderLatch(
      client, config.zooKeeperLeaderPath + "-curator", hostPort, LeaderLatch.CloseMode.NOTIFY_LEADER
    ))
    maybeLatch.get.addListener(Listener)
    maybeLatch.get.start()
  }

  private object Listener extends LeaderLatchListener {
    override def notLeader(): Unit = CuratorElectionService.this.synchronized {
      log.info(s"Defeated (LeaderLatchListener Interface). New leader: ${leaderHostPort.getOrElse("-")}")

      // remove tombstone for twitter commons
      twitterCommonsTombstone.delete(onlyMyself = true)

      stopLeadership()
    }

    override def isLeader(): Unit = CuratorElectionService.this.synchronized {
      log.info("Elected (LeaderLatchListener Interface)")
      startLeadership(error => CuratorElectionService.this.synchronized {
        maybeLatch match {
          case None => log.error("Abdicating leadership while not being leader")
          case Some(l) =>
            maybeLatch = None
            l.close()
        }
        // stopLeadership() is called in notLeader
      })

      // write a tombstone into the old twitter commons leadership election path which always
      // wins the selection. Check that startLeadership was successful and didn't abdicate.
      if (CuratorElectionService.this.isLeader) {
        twitterCommonsTombstone.create()
      }
    }
  }

  private def provideCuratorClient(): CuratorFramework = {
    log.info(s"Will do leader election through ${config.zkHosts}")
    val client = CuratorFrameworkFactory.builder().
      connectString(config.zkHosts).
      sessionTimeoutMs(config.zooKeeperSessionTimeout().toInt).
      retryPolicy(new RetryPolicy {
        override def allowRetry(retryCount: Int, elapsedTimeMs: Long, sleeper: RetrySleeper): Boolean = {
          log.error("ZooKeeper access failed")
          log.error("Committing suicide to avoid invalidating ZooKeeper state")

          val f = Future {
            // scalastyle:off magic.number
            Runtime.getRuntime.exit(9)
            // scalastyle:on
          }(scala.concurrent.ExecutionContext.global)

          try {
            Await.result(f, 5.seconds)
          }
          catch {
            case _: Throwable =>
              log.error("Finalization failed, killing JVM.")
              // scalastyle:off magic.number
              Runtime.getRuntime.halt(1)
            // scalastyle:on
          }

          false
        }
      }).
      build()
    client.start()
    client.getZookeeperClient.blockUntilConnectedOrTimedOut()
    client
  }

  private object twitterCommonsTombstone {
    lazy val acl = ZooDefs.Ids.OPEN_ACL_UNSAFE

    def memberPath(member: String): String = {
      config.zooKeeperLeaderPath.stripSuffix("/") + "/" + member
    }

    // - precedes 0-9 in ASCII and hence this instance overrules other candidates
    lazy val memberName = "member_-00000000"
    lazy val path = memberPath(memberName)

    var fallbackCreated = false

    def create(): Unit = {
      try {
        delete(onlyMyself = false)

        client.createContainers(config.zooKeeperLeaderPath)

        // Create a ephemeral node which is not removed when loosing leadership. This is necessary to avoid a
        // race of old Marathon instances which think that they can become leader in the moment
        // the new instances failover and no tombstone is existing (yet).
        if (!fallbackCreated) {
          client.create().
            creatingParentsIfNeeded().
            withMode(CreateMode.EPHEMERAL_SEQUENTIAL).
            withACL(acl).
            forPath(memberPath("member_-1"), hostPort.getBytes("UTF-8"))
          fallbackCreated = true
        }

        log.info("Creating tombstone for old twitter commons leader election")
        client.create().
          creatingParentsIfNeeded().
          withMode(CreateMode.EPHEMERAL).
          withACL(acl).
          forPath(path, hostPort.getBytes("UTF-8"))
      }
      catch {
        case e: Exception =>
          log.error(s"Exception while creating tombstone for twitter commons leader election: ${e.getMessage}")
          abdicateLeadership(error = true)
      }
    }

    def delete(onlyMyself: Boolean = false): Unit = {
      Option(client.checkExists().forPath(path)) match {
        case None =>
        case Some(tombstone) =>
          try {
            if (!onlyMyself || client.getData.forPath(memberPath(memberName)).toString == hostPort) {
              log.info("Deleting existing tombstone for old twitter commons leader election")
              client.delete().guaranteed().withVersion(tombstone.getVersion).forPath(path)
            }
          }
          catch {
            case _: KeeperException.NoNodeException     =>
            case _: KeeperException.BadVersionException =>
          }
      }
    }
  }
}
