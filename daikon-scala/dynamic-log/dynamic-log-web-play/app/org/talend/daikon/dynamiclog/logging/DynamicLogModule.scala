package org.talend.daikon.dynamiclog.logging

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.pubsub.DistributedPubSub
import com.google.inject.Provides
import javax.inject.Named
import net.codingwell.scalaguice.ScalaModule
import org.talend.daikon.dynamiclog.logging.actors.LoggerLevelActor
import org.talend.daikon.dynamiclog.logging.common.ActorNames._
import play.api.libs.concurrent.AkkaGuiceSupport

class DynamicLogModule extends ScalaModule with AkkaGuiceSupport {

  override def configure(): Unit = {
    bindActor[LoggerLevelActor](LOGGER_LEVEL_ACTOR)
  }

  @Provides
  @Named(DISTRIBUTED_PUB_SUB_MEDIATOR)
  def providesDistributedPubSubMediator(actorSystem: ActorSystem): ActorRef =
    DistributedPubSub(actorSystem).mediator
}
