/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration._

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.internal.delivery.DurableProducerQueue.MessageSent
import akka.actor.typed.internal.delivery.ProducerController.MessageWithConfirmation
import org.scalatest.WordSpecLike

class DurableProducerControllerSpec extends ScalaTestWithActorTestKit with WordSpecLike with LogCapturing {
  import TestConsumer.sequencedMessage
  import DurableProducerQueue.NoQualifier

  private var idCount = 0
  private def nextId(): Int = {
    idCount += 1
    idCount
  }

  private def producerId: String = s"p-$idCount"

  "ProducerController with durable queue" must {

    "load initial state and resend unconfirmed" in {
      nextId()
      val consumerControllerProbe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()

      val durable = TestDurableProducerQueue[TestConsumer.Job](
        Duration.Zero,
        DurableProducerQueue.State(
          currentSeqNr = 5,
          highestConfirmedSeqNr = 2,
          confirmedSeqNr = Map(NoQualifier -> 2),
          unconfirmed = Vector(
            DurableProducerQueue.MessageSent(3, TestConsumer.Job("msg-3"), false, NoQualifier),
            DurableProducerQueue.MessageSent(4, TestConsumer.Job("msg-4"), false, NoQualifier))))

      val producerController =
        spawn(ProducerController[TestConsumer.Job](producerId, Some(durable)), s"producerController-${idCount}")
          .unsafeUpcast[ProducerController.InternalCommand]
      val producerProbe = createTestProbe[ProducerController.RequestNext[TestConsumer.Job]]()
      producerController ! ProducerController.Start(producerProbe.ref)

      producerController ! ProducerController.RegisterConsumer(consumerControllerProbe.ref)

      // no request to producer since it has unconfirmed to begin with
      producerProbe.expectNoMessage()

      consumerControllerProbe.expectMessage(
        sequencedMessage(producerId, 3, producerController).copy(first = true)(producerController))
      consumerControllerProbe.expectNoMessage(50.millis)
      producerController ! ProducerController.Internal.Request(3L, 13L, true, false)
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 4, producerController))

      val sendTo = producerProbe.receiveMessage().sendNextTo
      sendTo ! TestConsumer.Job("msg-5")
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 5, producerController))

      testKit.stop(producerController)
    }

    "store confirmations" in {
      nextId()
      val consumerControllerProbe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()

      val stateHolder =
        new AtomicReference[DurableProducerQueue.State[TestConsumer.Job]](DurableProducerQueue.State.empty)
      val durable = TestDurableProducerQueue[TestConsumer.Job](
        Duration.Zero,
        stateHolder,
        (_: DurableProducerQueue.Command[_]) => false)

      val producerController =
        spawn(ProducerController[TestConsumer.Job](producerId, Some(durable)), s"producerController-${idCount}")
          .unsafeUpcast[ProducerController.InternalCommand]
      val producerProbe = createTestProbe[ProducerController.RequestNext[TestConsumer.Job]]()
      producerController ! ProducerController.Start(producerProbe.ref)

      producerController ! ProducerController.RegisterConsumer(consumerControllerProbe.ref)

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-1")
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 1, producerController))
      producerProbe.awaitAssert {
        stateHolder.get() should ===(
          DurableProducerQueue
            .State(2, 0, Map.empty, Vector(MessageSent(1, TestConsumer.Job("msg-1"), ack = false, NoQualifier))))
      }
      producerController ! ProducerController.Internal.Request(1L, 10L, true, false)
      producerProbe.awaitAssert {
        stateHolder.get() should ===(DurableProducerQueue.State(2, 1, Map(NoQualifier -> 1), Vector.empty))
      }

      val replyTo = createTestProbe[Long]()
      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-2"), replyTo.ref)
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 2, producerController, ack = true))
      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-3"), replyTo.ref)
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 3, producerController, ack = true))
      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-4"), replyTo.ref)
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 4, producerController, ack = true))
      producerController ! ProducerController.Internal.Ack(3)
      producerProbe.awaitAssert {
        stateHolder.get() should ===(
          DurableProducerQueue.State(
            5,
            3,
            Map(NoQualifier -> 3),
            Vector(MessageSent(4, TestConsumer.Job("msg-4"), ack = true, NoQualifier))))
      }

      testKit.stop(producerController)
    }

    "reply to MessageWithConfirmation after storage" in {
      nextId()
      val consumerControllerProbe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()

      val durable =
        TestDurableProducerQueue[TestConsumer.Job](Duration.Zero, DurableProducerQueue.State.empty[TestConsumer.Job])

      val producerController =
        spawn(ProducerController[TestConsumer.Job](producerId, Some(durable)), s"producerController-${idCount}")
          .unsafeUpcast[ProducerController.InternalCommand]
      val producerProbe = createTestProbe[ProducerController.RequestNext[TestConsumer.Job]]()
      producerController ! ProducerController.Start(producerProbe.ref)

      producerController ! ProducerController.RegisterConsumer(consumerControllerProbe.ref)

      val replyTo = createTestProbe[Long]()

      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-1"), replyTo.ref)
      replyTo.expectMessage(1L)

      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 1, producerController, ack = true))
      producerController ! ProducerController.Internal.Request(1L, 10L, true, false)

      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-2"), replyTo.ref)
      replyTo.expectMessage(2L)

      testKit.stop(producerController)
    }
  }

}
