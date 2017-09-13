package filodb.coordinator

import scala.collection.mutable.{HashMap => MutableHashMap}
import akka.actor._
import akka.event.LoggingReceive
import filodb.coordinator.NodeClusterActor.{IngestionSource, SetupDataset}
import filodb.core.DatasetRef
import filodb.core.metadata.{Column, Dataset}

/** This actor manages the following for its parent, the cluster singleton,
  * [[filodb.coordinator.NodeClusterActor]]:
  *  1. All [[filodb.coordinator.ShardMapper]]s
  *  2. Subscriptions for dataset shard state events
  *  3. Current Subscribers for shard state events via Deathwatch
  *  4. Publishes ShardEvents to subscribers of the shard event's dataset
  *
  * Some subscribers are node coordinator actors, their deathwatch also is done
  * here in order to update the shard node status directly.
  */
private[coordinator] final class ShardCoordinatorActor(strategy: ShardAssignmentStrategy) extends NamingAwareBaseActor {

  import ShardSubscriptions._
  import NodeClusterActor.{GetShardMap, DatasetUnknown}

  val shardMappers = new MutableHashMap[DatasetRef, ShardMapper] // when this gets too big

  var subscriptions = ShardSubscriptions(Set.empty)

  override def receive: Actor.Receive = LoggingReceive {
    case e: AddDataset           => addDataset(e, sender())
    case e: SubscribeCoordinator => subscribe(e, sender())
    case e: Subscribe            => subscribe(e, sender())
    case e: ShardEvent           => publish(e, sender())
    case RemoveSubscription(ds)  => remove(ds)
    case Terminated(actor)       => terminated(actor)
    case Unsubscribe(actor)      => unsubscribe(actor)
    case GetSubscribers(ds)      => subscribers(ds, sender())
    case GetSubscriptions        => subscriptions(sender())
    case NodeProtocol.ResetState => reset(sender())
    case GetShardMap(ref)        => getMapper(ref, sender())
  }

  /** Selects the `ShardMapper` for the provided dataset, updates the mapper
    * for the received shard event from the event source, and publishes
    * the event to all subscribers of that event and dataset.
    */
  private def publish(e: ShardEvent, publisher: ActorRef): Unit =
    for {
      mapper       <- shardMappers.get(e.ref)
      _            = mapper.updateFromEvent(e)
      subscription <- subscriptions.subscription(e.ref)
    } subscription.subscribers foreach (_ ! e)

  /** Sent from the [[filodb.coordinator.NodeClusterActor]] on SetupDataset.
    * If the dataset subscription exists, returns a DatasetExists to the cluster
    * actor, otherwise adds the new dataset (subscription) via the shard
    * assignment strategy. Subscribes all known [[akka.cluster.ClusterEvent.MemberUp]]
    * members in the ring to the new subscription. Sends the cluster actor a
    * `SubscriptionAdded` to proceed in the dataset's setup. Sends the commands
    * from the assignment strategy to the provided member coordinators.
    *
    * INTERNAL API. Idempotent.
    */
  private def addDataset(e: AddDataset, origin: ActorRef): Unit =
    shardMappers.get(e.setup.ref) match {
      case Some(exists) =>
        e.ackTo ! NodeClusterActor.DatasetExists(e.setup.ref)
      case _ =>

        val added = strategy.datasetAdded(e.setup.ref, e.setup.resources, shardMappers)

        shardMappers(added.ref) = added.mapper
        subscriptions :+= ShardSubscription(added.ref, e.coordinators)
        logger.info(s"Dataset '${added.ref}' added, created new ${added.mapper}")

        origin ! DatasetAdded(e.dataset, e.columns, e.setup.source, added.shards, e.ackTo)
    }

  /** Shard assignment strategy adds the new node and returns the `ShardsAssigned`.
    * The set up local `ShardMapper`s are updated. Sends `CoordinatorSubscribed`
    * to initiate [[filodb.coordinator.NodeClusterActor.sendDatasetSetup]].
    * The new Coordinator is subscribed to all registered subscriptions (datasets).
    *
    * INTERNAL API.
    */
  private def subscribe(e: SubscribeCoordinator, origin: ActorRef): Unit = {
    val assigned = strategy.nodeAdded(e.subscriber, shardMappers)

    assigned.shards foreach { dshards =>
      shardMappers(dshards.ref) = dshards.mapper
      subscribe(e.subscriber, dshards.ref)
    }

    origin ! CoordinatorSubscribed(e.subscriber, assigned.datasets)
  }

  /** If the mapper for the provided `datasetRef` has been added, sends an initial
    * current snapshot of partition state, as ingestion will subscribe usually when
    * the cluster is already stable.
    *
    * This function is called in two cases: when a client sends the cluster actor
    * a `SubscribeShardUpdates`, and when a coordinator creates the memstore
    * and query actor for a newly-registered dataset and sends the shard actor
    * a subscribe for the query actor. In the first case there is no guarantee
    * that the dataset is setup, in the second there is.
    *
    * INTERNAL API. Idempotent.
    */
  private def subscribe(e: Subscribe, origin: ActorRef): Unit =
    shardMappers.get(e.dataset) match {
      case Some(mapper) =>
        subscribe(e.subscriber, e.dataset)
        e.subscriber ! CurrentShardSnapshot(e.dataset, mapper)
      case _ =>
        origin ! SubscriptionUnknown(e.dataset, e.subscriber)
    }

  private def getMapper(ref: DatasetRef, originator: ActorRef): Unit =
    shardMappers.get(ref) match {
      case Some(mapper) => originator ! mapper
      case _            => originator ! DatasetUnknown(ref)
    }

  /** Removes the terminated `actor` which can either be a subscriber
    * or a subscription worker.
    *
    * INTERNAL API. Idempotent.
    */
  def terminated(actor: ActorRef): Unit = {
    unsubscribe(actor)

    if (isCoordinator(actor)) {
      val shards = strategy.nodeRemoved(actor, shardMappers).shards

      for {
        (ds, mapper)         <- shardMappers
        (shards, updatedMap) <- shards.get(ds)
      } shardMappers(ds) = updatedMap
    }
  }

  /** Subscribes a subscriber to an existing dataset's shard updates.
    * Idempotent.
    */
  private def subscribe(subscriber: ActorRef, dataset: DatasetRef): Unit = {
    subscriptions = subscriptions.subscribe(subscriber, dataset)
    context watch subscriber
  }

  /**
    * Unsubscribes a subscriber from all dataset shard updates.
    *
    * INTERNAL API. Idempotent.
    *
    * @param subscriber the cluster member removed from the cluster
    *                or regular subscriber unsubscribing
    */
  private def unsubscribe(subscriber: ActorRef): Unit = {
    subscriptions = subscriptions unsubscribe subscriber
    require(subscriptions.isRemoved(subscriber))
    context unwatch subscriber
  }

  /** Sends subscribers for the dataset to the requester. If the subscription
    * does not exist the subscribers will be empty.
    *
    * INTERNAL API. Read-only.
    */
  private def subscribers(ds: DatasetRef, origin: ActorRef): Unit =
    origin ! Subscribers(subscriptions.subscribers(ds), ds)

  /** Sends subscriptions to requester.
    *
    * INTERNAL API. Read-only.
    */
  private def subscriptions(origin: ActorRef): Unit =
    origin ! subscriptions

  /** Removes the dataset from subscriptions, if exists.
    *
    * INTERNAL API. Idempotent.
    *
    * @param dataset the dataset to remove if it was setup
    */
  private def remove(dataset: DatasetRef): Unit = {
    subscriptions = subscriptions - dataset
    shardMappers remove dataset
  }

  /** Resets all state.
    * INTERNAL API.
    */
  private def reset(origin: ActorRef): Unit = {
    shardMappers.clear()
    subscriptions = subscriptions.clear
    origin ! NodeProtocol.StateReset
  }
}

object ShardSubscriptions {

  final case class Subscribers(subscribers: Set[ActorRef], dataset: DatasetRef)

  sealed trait SubscriptionProtocol
  sealed trait ShardAssignmentProtocol

  /** Command to add a subscription. */
  private[coordinator] final case class AddDataset(setup: SetupDataset,
                                                   dataset: Dataset,
                                                   columns: Seq[Column],
                                                   coordinators: Set[ActorRef],
                                                   ackTo: ActorRef
                                                  ) extends ShardAssignmentProtocol

  /** Ack by ShardStatusActor to it's parent, [[filodb.coordinator.NodeClusterActor]],
    * upon it sending `SubscribeCoordinator`. Command to start ingestion for dataset.
    */
  private[coordinator] final case class DatasetAdded(dataset: Dataset,
                                                     columns: Seq[Column],
                                                     source: IngestionSource,
                                                     shards: Map[ActorRef, Seq[Int]],
                                                     ackTo: ActorRef
                                                    ) extends ShardAssignmentProtocol

  sealed trait SubscribeCommand extends SubscriptionProtocol {
    def subscriber: ActorRef
  }

  /** Usable by FiloDB clients.
    * Internally used by Coordinators to subscribe a new Query actor to it's dataset.
    *
    * @param subscriber the actor subscribing to the `ShardMapper` status updates
    * @param dataset    the `DatasetRef` key for the `ShardMapper`
    */
  final case class Subscribe(subscriber: ActorRef, dataset: DatasetRef) extends SubscribeCommand

  /** Used only by the cluster actor to subscribe coordinators to all datasets.
    * INTERNAL API.
    */
  private[coordinator] final case class SubscribeCoordinator(subscriber: ActorRef) extends SubscribeCommand

  /** Ack returned by shard actor to cluster actor on successful coordinator subscribe. */
  private[coordinator] final case class CoordinatorSubscribed(
    coordinator: ActorRef, updated: Seq[DatasetRef]) extends SubscriptionProtocol

  /** Returned to cluster actor subscribing on behalf of a coordinator or subscriber
    * or to the coordinator subscribing a query actor on create, if the dataset is
    * unrecognized. Similar to [[filodb.coordinator.NodeClusterActor.DatasetUnknown]]
    * but requires the actor being subscribed for tracking.
    */
  private[coordinator] final case class SubscriptionUnknown(
    dataset: DatasetRef, subscriber: ActorRef) extends SubscriptionProtocol

  /** Unsubscribes a subscriber. */
  final case class Unsubscribe(subscriber: ActorRef) extends SubscriptionProtocol

  private[coordinator] final case class GetSubscribers(dataset: DatasetRef) extends SubscriptionProtocol

  private[coordinator] final case class RemoveSubscription(dataset: DatasetRef) extends SubscriptionProtocol

  private[coordinator] case object GetSubscriptions extends SubscriptionProtocol

  private[coordinator] case object Reset extends SubscriptionProtocol
  private[coordinator] case object ResetComplete extends SubscriptionProtocol

}

private[coordinator] final case class ShardSubscriptions(subscriptions: Set[ShardSubscription]) {

  def subscribe(subscriber: ActorRef, to: DatasetRef): ShardSubscriptions =
    subscription(to).map { sub =>
      copy(subscriptions = (subscriptions - sub) + (sub + subscriber))
    }.getOrElse(this)

  def unsubscribe(subscriber: ActorRef): ShardSubscriptions =
    copy(subscriptions = subscriptions.map(_ - subscriber))

  def subscription(dataset: DatasetRef): Option[ShardSubscription] =
    subscriptions.collectFirst { case s if s.dataset == dataset => s }

  def subscribers(dataset: DatasetRef): Set[ActorRef] =
    subscription(dataset).map(_.subscribers).getOrElse(Set.empty)

  //scalastyle:off method.name
  def :+(s: ShardSubscription): ShardSubscriptions =
    subscription(s.dataset).map(x => this)
      .getOrElse(copy(subscriptions = this.subscriptions + s))

  def -(dataset: DatasetRef): ShardSubscriptions =
    subscription(dataset).map { s =>
      copy(subscriptions = this.subscriptions - s)}
      .getOrElse(this)

  def isRemoved(downed: ActorRef): Boolean =
    subscriptions.forall(s => !s.subscribers.contains(downed))

  def clear: ShardSubscriptions =
    this copy (subscriptions = Set.empty)

}

private[coordinator] final case class ShardSubscription(dataset: DatasetRef, subscribers: Set[ActorRef]) {

  def +(subscriber: ActorRef): ShardSubscription =
    copy(subscribers = this.subscribers + subscriber)

  def -(subscriber: ActorRef): ShardSubscription =
    copy(subscribers = this.subscribers - subscriber)

}