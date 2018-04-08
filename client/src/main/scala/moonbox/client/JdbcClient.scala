package moonbox.client

import java.io.IOException
import java.net.SocketAddress
import java.util.UUID
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

import io.netty.bootstrap.Bootstrap
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.serialization.{ClassResolvers, ObjectDecoder, ObjectEncoder}
import io.netty.util.concurrent.EventExecutorGroup
import moonbox.common.MbLogging
import moonbox.common.message.{EchoInbound, EchoOutbound, JdbcOutboundMessage}

class JdbcClient(host: String, port: Int) extends MbLogging {

  private var channel: Channel = _
  private var handler: JdbcClientHandler = _
  private val messageId = new AtomicLong()
  private var ws: EventExecutorGroup = _

  connect()

  def connect(): Unit = {
    try {
      val workerGroup = new NioEventLoopGroup()
      ws = workerGroup
      val b = new Bootstrap()
      handler = new JdbcClientHandler
      b.group(workerGroup)
        .channel(classOf[NioSocketChannel])
        .option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)
        .option[java.lang.Boolean](ChannelOption.TCP_NODELAY, true)
        .handler(new ChannelInitializer[SocketChannel]() {
          override def initChannel(ch: SocketChannel) = {
            ch.pipeline.addLast(new ObjectEncoder, new ObjectDecoder(ClassResolvers.cacheDisabled(null)), handler)
          }
        })
      val cf = b.connect(host, port).syncUninterruptibly()
      if (!cf.await(JdbcClient.CONNECT_TIMEOUT))
        throw new IOException(s"Connecting to $host timed out (${JdbcClient.CONNECT_TIMEOUT} ms)")
      else if (cf.cause != null)
        throw new IOException(s"Failed to connect to $host", cf.cause)
      this.channel = cf.channel
      logInfo(s"Connected to ${channel.remoteAddress()}")
    } catch {
      case e: Exception =>
        logError(e.getMessage)
        this.close()
    }
  }

  def getRemoteAddress: SocketAddress = {
    if (channel != null) channel.remoteAddress()
    else throw new ChannelException("channel unestablished")
  }

  def getMessageId(): Long = messageId.getAndIncrement()

  def sendOneWayMessage(msg: Any) = handler.send(msg)

  def sendAndReceive(msg: Any, timeout: Long): JdbcOutboundMessage = handler.sendAndReceive(msg, timeout)

  def sendWithCallback(msg: Any, callback: => JdbcOutboundMessage => Any) = handler.send(msg, callback)

  def close() = {
    if (ws != null) {
      ws.shutdownGracefully()
    }
    if (channel != null) {
      channel.close()
    }
  }
}

object JdbcClient {
  val CONNECT_TIMEOUT = 5000
  val RESULT_RESPONSE_TIMEOUT = 5000
  val host = "localhost"
  val port = 10010
  val client = new JdbcClient(host, port)
  val CYCLE_COUNT = 10

  def main(args: Array[String]): Unit = {
    var nullCounter: Long = 0
    var count = 0
    var seq = Seq.empty[(String, Int)]
    while (count < CYCLE_COUNT) {
      val content = UUID.randomUUID().toString
      val msgId = client.getMessageId()
      val message = EchoInbound(msgId, content)
      val resp = client.sendAndReceive(message, RESULT_RESPONSE_TIMEOUT)
      if (resp == null) {
        seq +:= ("null id: ", count)
        nullCounter += 1
      } else
        println(s"message $msgId: $message,  response ${resp.asInstanceOf[EchoOutbound].messageId}: $resp")
      count += 1
    }
    println(s"nullCounter = $nullCounter")
    seq.foreach(println)
    client.close()
  }
}