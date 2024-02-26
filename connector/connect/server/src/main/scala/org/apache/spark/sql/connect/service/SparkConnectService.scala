/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.connect.service

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters._

import com.google.protobuf.MessageLite
import io.grpc.{BindableService, MethodDescriptor, Server, ServerMethodDefinition, ServerServiceDefinition}
import io.grpc.MethodDescriptor.PrototypeMarshaller
import io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.lite.ProtoLiteUtils
import io.grpc.protobuf.services.ProtoReflectionService
import io.grpc.stub.StreamObserver
import org.apache.commons.lang3.StringUtils

import org.apache.spark.{SparkContext, SparkEnv}
import org.apache.spark.connect.proto
import org.apache.spark.connect.proto.{AddArtifactsRequest, AddArtifactsResponse, SparkConnectServiceGrpc}
import org.apache.spark.connect.proto.SparkConnectServiceGrpc.AsyncService
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config.UI.UI_ENABLED
import org.apache.spark.sql.connect.config.Connect.{CONNECT_GRPC_BINDING_ADDRESS, CONNECT_GRPC_BINDING_PORT, CONNECT_GRPC_MARSHALLER_RECURSION_LIMIT, CONNECT_GRPC_MAX_INBOUND_MESSAGE_SIZE}
import org.apache.spark.sql.connect.ui.{SparkConnectServerAppStatusStore, SparkConnectServerListener, SparkConnectServerTab}
import org.apache.spark.sql.connect.utils.ErrorUtils
import org.apache.spark.status.ElementTrackingStore

/**
 * The SparkConnectService implementation.
 *
 * This class implements the service stub from the generated code of GRPC.
 *
 * @param debug
 *   delegates debug behavior to the handlers.
 */
class SparkConnectService(debug: Boolean) extends AsyncService with BindableService with Logging {

  /**
   * This is the main entry method for Spark Connect and all calls to execute a plan.
   *
   * The plan execution is delegated to the [[SparkConnectExecutePlanHandler]]. All error handling
   * should be directly implemented in the deferred implementation. But this method catches
   * generic errors.
   *
   * @param request
   * @param responseObserver
   */
  override def executePlan(
      request: proto.ExecutePlanRequest,
      responseObserver: StreamObserver[proto.ExecutePlanResponse]): Unit = {
    try {
      new SparkConnectExecutePlanHandler(responseObserver).handle(request)
    } catch {
      ErrorUtils.handleError(
        "execute",
        observer = responseObserver,
        userId = request.getUserContext.getUserId,
        sessionId = request.getSessionId)
    }
  }

  /**
   * Analyze a plan to provide metadata and debugging information.
   *
   * This method is called to generate the explain plan for a SparkConnect plan. In its simplest
   * implementation, the plan that is generated by the [[SparkConnectPlanner]] is used to build a
   * [[Dataset]] and derive the explain string from the query execution details.
   *
   * Errors during planning are returned via the [[StreamObserver]] interface.
   *
   * @param request
   * @param responseObserver
   */
  override def analyzePlan(
      request: proto.AnalyzePlanRequest,
      responseObserver: StreamObserver[proto.AnalyzePlanResponse]): Unit = {
    try {
      new SparkConnectAnalyzeHandler(responseObserver).handle(request)
    } catch {
      ErrorUtils.handleError(
        "analyze",
        observer = responseObserver,
        userId = request.getUserContext.getUserId,
        sessionId = request.getSessionId)
    }
  }

  /**
   * This is the main entry method for Spark Connect and all calls to update or fetch
   * configuration..
   *
   * @param request
   * @param responseObserver
   */
  override def config(
      request: proto.ConfigRequest,
      responseObserver: StreamObserver[proto.ConfigResponse]): Unit = {
    try {
      new SparkConnectConfigHandler(responseObserver).handle(request)
    } catch {
      ErrorUtils.handleError(
        "config",
        observer = responseObserver,
        userId = request.getUserContext.getUserId,
        sessionId = request.getSessionId)
    }
  }

  /**
   * This is the main entry method for all calls to add/transfer artifacts.
   *
   * @param responseObserver
   * @return
   */
  override def addArtifacts(responseObserver: StreamObserver[AddArtifactsResponse])
      : StreamObserver[AddArtifactsRequest] = new SparkConnectAddArtifactsHandler(
    responseObserver)

  /**
   * This is the entry point for all calls of getting artifact statuses.
   */
  override def artifactStatus(
      request: proto.ArtifactStatusesRequest,
      responseObserver: StreamObserver[proto.ArtifactStatusesResponse]): Unit = {
    try {
      new SparkConnectArtifactStatusesHandler(responseObserver).handle(request)
    } catch
      ErrorUtils.handleError(
        "artifactStatus",
        observer = responseObserver,
        userId = request.getUserContext.getUserId,
        sessionId = request.getSessionId)
  }

  /**
   * This is the entry point for calls interrupting running executions.
   */
  override def interrupt(
      request: proto.InterruptRequest,
      responseObserver: StreamObserver[proto.InterruptResponse]): Unit = {
    try {
      new SparkConnectInterruptHandler(responseObserver).handle(request)
    } catch
      ErrorUtils.handleError(
        "interrupt",
        observer = responseObserver,
        userId = request.getUserContext.getUserId,
        sessionId = request.getSessionId)
  }

  /**
   * Reattach and continue an ExecutePlan reattachable execution.
   */
  override def reattachExecute(
      request: proto.ReattachExecuteRequest,
      responseObserver: StreamObserver[proto.ExecutePlanResponse]): Unit = {
    try {
      new SparkConnectReattachExecuteHandler(responseObserver).handle(request)
    } catch
      ErrorUtils.handleError(
        "reattachExecute",
        observer = responseObserver,
        userId = request.getUserContext.getUserId,
        sessionId = request.getSessionId)
  }

  /**
   * Release reattachable execution - either part of buffered response, or finish and release all.
   */
  override def releaseExecute(
      request: proto.ReleaseExecuteRequest,
      responseObserver: StreamObserver[proto.ReleaseExecuteResponse]): Unit = {
    try {
      new SparkConnectReleaseExecuteHandler(responseObserver).handle(request)
    } catch
      ErrorUtils.handleError(
        "releaseExecute",
        observer = responseObserver,
        userId = request.getUserContext.getUserId,
        sessionId = request.getSessionId)
  }

  /**
   * Release session.
   */
  override def releaseSession(
      request: proto.ReleaseSessionRequest,
      responseObserver: StreamObserver[proto.ReleaseSessionResponse]): Unit = {
    try {
      new SparkConnectReleaseSessionHandler(responseObserver).handle(request)
    } catch
      ErrorUtils.handleError(
        "releaseSession",
        observer = responseObserver,
        userId = request.getUserContext.getUserId,
        sessionId = request.getSessionId)
  }

  override def fetchErrorDetails(
      request: proto.FetchErrorDetailsRequest,
      responseObserver: StreamObserver[proto.FetchErrorDetailsResponse]): Unit = {
    try {
      new SparkConnectFetchErrorDetailsHandler(responseObserver).handle(request)
    } catch {
      ErrorUtils.handleError(
        "getErrorInfo",
        observer = responseObserver,
        userId = request.getUserContext.getUserId,
        sessionId = request.getSessionId)
    }
  }

  private def methodWithCustomMarshallers(methodDesc: MethodDescriptor[MessageLite, MessageLite])
      : MethodDescriptor[MessageLite, MessageLite] = {
    val recursionLimit =
      SparkEnv.get.conf.get(CONNECT_GRPC_MARSHALLER_RECURSION_LIMIT)
    val requestMarshaller =
      ProtoLiteUtils.marshallerWithRecursionLimit(
        methodDesc.getRequestMarshaller
          .asInstanceOf[PrototypeMarshaller[MessageLite]]
          .getMessagePrototype,
        recursionLimit)
    val responseMarshaller =
      ProtoLiteUtils.marshallerWithRecursionLimit(
        methodDesc.getResponseMarshaller
          .asInstanceOf[PrototypeMarshaller[MessageLite]]
          .getMessagePrototype,
        recursionLimit)
    methodDesc.toBuilder
      .setRequestMarshaller(requestMarshaller)
      .setResponseMarshaller(responseMarshaller)
      .build()
  }

  override def bindService(): ServerServiceDefinition = {
    // First, get the SparkConnectService ServerServiceDefinition.
    val serviceDef = SparkConnectServiceGrpc.bindService(this)

    // Create a new ServerServiceDefinition builder
    // using the name of the original service definition.
    val builder = io.grpc.ServerServiceDefinition.builder(serviceDef.getServiceDescriptor.getName)

    // Iterate through all the methods of the original service definition.
    // For each method, add a customized method descriptor (with updated marshallers)
    // and the original server call handler to the builder.
    serviceDef.getMethods.asScala
      .asInstanceOf[Iterable[ServerMethodDefinition[MessageLite, MessageLite]]]
      .foreach(method =>
        builder.addMethod(
          methodWithCustomMarshallers(method.getMethodDescriptor),
          method.getServerCallHandler))

    // Build the final ServerServiceDefinition and return it.
    builder.build()
  }
}

/**
 * Static instance of the SparkConnectService.
 *
 * Used to start the overall SparkConnect service and provides global state to manage the
 * different SparkSession from different users connecting to the cluster.
 */
object SparkConnectService extends Logging {

  private[connect] var server: Server = _

  private[connect] var uiTab: Option[SparkConnectServerTab] = None
  private[connect] var listener: SparkConnectServerListener = _

  // For testing purpose, it's package level private.
  private[connect] def localPort: Int = {
    assert(server != null)
    // Return the actual local port being used. This can be different from the configured port
    // when the server binds to the port 0 as an example.
    server.getPort
  }

  private[connect] lazy val executionManager = new SparkConnectExecutionManager()

  private[connect] lazy val sessionManager = new SparkConnectSessionManager()

  private[connect] val streamingSessionManager =
    new SparkConnectStreamingQueryCache()

  /**
   * Based on the userId and sessionId, find or create a new SparkSession.
   */
  def getOrCreateIsolatedSession(userId: String, sessionId: String): SessionHolder = {
    sessionManager.getOrCreateIsolatedSession(SessionKey(userId, sessionId))
  }

  /**
   * If there are no executions, return Left with System.currentTimeMillis of last active
   * execution. Otherwise return Right with list of ExecuteInfo of all executions.
   */
  def listActiveExecutions: Either[Long, Seq[ExecuteInfo]] = executionManager.listActiveExecutions

  private def createListenerAndUI(sc: SparkContext): Unit = {
    val kvStore = sc.statusStore.store.asInstanceOf[ElementTrackingStore]
    listener = new SparkConnectServerListener(kvStore, sc.conf)
    sc.listenerBus.addToStatusQueue(listener)
    uiTab = if (sc.getConf.get(UI_ENABLED)) {
      Some(
        new SparkConnectServerTab(
          new SparkConnectServerAppStatusStore(kvStore),
          SparkConnectServerTab.getSparkUI(sc)))
    } else {
      None
    }
  }

  /**
   * Starts the GRPC Service.
   */
  private def startGRPCService(): Unit = {
    val debugMode = SparkEnv.get.conf.getBoolean("spark.connect.grpc.debug.enabled", true)
    val bindAddress = SparkEnv.get.conf.get(CONNECT_GRPC_BINDING_ADDRESS)
    val port = SparkEnv.get.conf.get(CONNECT_GRPC_BINDING_PORT)
    val sb = bindAddress match {
      case Some(hostname) =>
        logInfo(s"start GRPC service at: $hostname")
        NettyServerBuilder.forAddress(new InetSocketAddress(hostname, port))
      case _ => NettyServerBuilder.forPort(port)
    }
    sb.maxInboundMessageSize(SparkEnv.get.conf.get(CONNECT_GRPC_MAX_INBOUND_MESSAGE_SIZE).toInt)
      .addService(new SparkConnectService(debugMode))

    // Add all registered interceptors to the server builder.
    SparkConnectInterceptorRegistry.chainInterceptors(sb)

    // If debug mode is configured, load the ProtoReflection service so that tools like
    // grpcurl can introspect the API for debugging.
    if (debugMode) {
      sb.addService(ProtoReflectionService.newInstance())
    }
    server = sb.build
    server.start()
  }

  // Starts the service
  def start(sc: SparkContext): Unit = {
    startGRPCService()
    createListenerAndUI(sc)
  }

  def stop(timeout: Option[Long] = None, unit: Option[TimeUnit] = None): Unit = {
    if (server != null) {
      if (timeout.isDefined && unit.isDefined) {
        server.shutdown()
        server.awaitTermination(timeout.get, unit.get)
      } else {
        server.shutdownNow()
      }
    }
    streamingSessionManager.shutdown()
    executionManager.shutdown()
    sessionManager.shutdown()
    uiTab.foreach(_.detach())
  }

  def extractErrorMessage(st: Throwable): String = {
    val message = StringUtils.abbreviate(st.getMessage, 2048)
    convertNullString(message)
  }

  def convertNullString(str: String): String = {
    if (str != null) {
      str
    } else {
      ""
    }
  }
}
