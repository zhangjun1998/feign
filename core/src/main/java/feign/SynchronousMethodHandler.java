/*
 * Copyright 2012-2022 The Feign Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import feign.InvocationHandlerFactory.MethodHandler;
import feign.Request.Options;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import static feign.ExceptionPropagationPolicy.UNWRAP;
import static feign.FeignException.errorExecuting;
import static feign.Util.checkNotNull;

/**
 * 方法处理器的实现类
 * 除了 default 类型的方法和需要被忽略的方法，接口中其它方法的处理器默认都是这个，该处理器是同步执行的
 *
 * @author ZhangJun
 * @create 2022/6/18 18:01
 */
final class SynchronousMethodHandler implements MethodHandler {

  private static final long MAX_RESPONSE_BUFFER_SIZE = 8192L;

  private final MethodMetadata metadata;
  private final Target<?> target;
  private final Client client;
  private final Retryer retryer;
  private final List<RequestInterceptor> requestInterceptors;
  private final Logger logger;
  private final Logger.Level logLevel;
  private final RequestTemplate.Factory buildTemplateFromArgs;
  private final Options options;
  private final ExceptionPropagationPolicy propagationPolicy;

  // only one of decoder and asyncResponseHandler will be non-null
  private final Decoder decoder;
  private final AsyncResponseHandler asyncResponseHandler;


  /**
   * 构造函数，填充各种属性
   */
  private SynchronousMethodHandler(Target<?> target, Client client, Retryer retryer,
      List<RequestInterceptor> requestInterceptors, Logger logger,
      Logger.Level logLevel, MethodMetadata metadata,
      RequestTemplate.Factory buildTemplateFromArgs, Options options,
      Decoder decoder, ErrorDecoder errorDecoder, boolean dismiss404,
      boolean closeAfterDecode, ExceptionPropagationPolicy propagationPolicy,
      boolean forceDecoding) {

    this.target = checkNotNull(target, "target");
    this.client = checkNotNull(client, "client for %s", target);
    this.retryer = checkNotNull(retryer, "retryer for %s", target);
    this.requestInterceptors =
        checkNotNull(requestInterceptors, "requestInterceptors for %s", target);
    this.logger = checkNotNull(logger, "logger for %s", target);
    this.logLevel = checkNotNull(logLevel, "logLevel for %s", target);
    this.metadata = checkNotNull(metadata, "metadata for %s", target);
    this.buildTemplateFromArgs = checkNotNull(buildTemplateFromArgs, "metadata for %s", target);
    this.options = checkNotNull(options, "options for %s", target);
    this.propagationPolicy = propagationPolicy;

    if (forceDecoding) {
      // internal only: usual handling will be short-circuited, and all responses will be passed to
      // decoder directly!
      this.decoder = decoder;
      this.asyncResponseHandler = null;
    } else {
      this.decoder = null;
      this.asyncResponseHandler = new AsyncResponseHandler(logLevel, logger, decoder, errorDecoder,
          dismiss404, closeAfterDecode);
    }
  }

  /**
   * 代理对象调用任意方法时被 InvocationHandlerFactory 拦截并分发到了这个处理器执行
   */
  @Override
  public Object invoke(Object[] argv) throws Throwable {
    // 创建 RequestTemplate
    RequestTemplate template = buildTemplateFromArgs.create(argv);

    // FeignClient 的 Options 配置和 Retryer 配置，一些超时、重试等配置
    Options options = findOptions(argv);
    Retryer retryer = this.retryer.clone();

    // 执行 HTTP 请求
    while (true) {
      try {
        // 执行请求并返回解码后的响应
        return executeAndDecode(template, options);
      } catch (RetryableException e) { // 重试异常，进行重试
        try {
          retryer.continueOrPropagate(e);
        } catch (RetryableException th) {
          Throwable cause = th.getCause();
          if (propagationPolicy == UNWRAP && cause != null) {
            throw cause;
          } else {
            throw th;
          }
        }
        if (logLevel != Logger.Level.NONE) {
          logger.logRetry(metadata.configKey(), logLevel);
        }
        continue;
      }
      // 其它异常直接抛出，退出方法
    }
  }

  /**
   * 执行 HTTP 请求并返回解码后的响应
   */
  Object executeAndDecode(RequestTemplate template, Options options) throws Throwable {
    // 执行请求的前置拦截器，并将 RequestTemplate 转换为 Request
    Request request = targetRequest(template);

    if (logLevel != Logger.Level.NONE) {
      logger.logRequest(metadata.configKey(), logLevel, request);
    }

    Response response;
    long start = System.nanoTime();
    try {
      // 将请求交给 HTTP 客户端执行并返回响应
      response = client.execute(request, options);
      // ensure the request is set. TODO: remove in Feign 12
      response = response.toBuilder()
          .request(request)
          .requestTemplate(template)
          .build();
    } catch (IOException e) {
      if (logLevel != Logger.Level.NONE) {
        logger.logIOException(metadata.configKey(), logLevel, e, elapsedTime(start));
      }
      throw errorExecuting(request, e);
    }

    // 统计请求耗时
    long elapsedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    // 解码响应并返回结果
    if (decoder != null) {
      return decoder.decode(response, metadata.returnType());
    }

    // 下面在干啥，异步？
    CompletableFuture<Object> resultFuture = new CompletableFuture<>();
    asyncResponseHandler.handleResponse(resultFuture, metadata.configKey(), response, metadata.returnType(), elapsedTime);

    try {
      if (!resultFuture.isDone())
        throw new IllegalStateException("Response handling not done");

      return resultFuture.join();
    } catch (CompletionException e) {
      Throwable cause = e.getCause();
      if (cause != null)
        throw cause;
      throw e;
    }
  }

  long elapsedTime(long start) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  /**
   * 执行请求前置拦截器，并转换 RequestTemplate 为 Request
   */
  Request targetRequest(RequestTemplate template) {
    for (RequestInterceptor interceptor : requestInterceptors) {
      interceptor.apply(template);
    }
    return target.apply(template);
  }

  Options findOptions(Object[] argv) {
    if (argv == null || argv.length == 0) {
      return this.options;
    }
    return Stream.of(argv)
        .filter(Options.class::isInstance)
        .map(Options.class::cast)
        .findFirst()
        .orElse(this.options);
  }

  static class Factory {

    private final Client client;
    private final Retryer retryer;
    private final List<RequestInterceptor> requestInterceptors;
    private final Logger logger;
    private final Logger.Level logLevel;
    private final boolean dismiss404;
    private final boolean closeAfterDecode;
    private final ExceptionPropagationPolicy propagationPolicy;
    private final boolean forceDecoding;

    /**
     * SynchronousMethodHandler 工厂类
     */
    Factory(Client client, Retryer retryer, List<RequestInterceptor> requestInterceptors,
        Logger logger, Logger.Level logLevel, boolean dismiss404, boolean closeAfterDecode,
        ExceptionPropagationPolicy propagationPolicy, boolean forceDecoding) {
      this.client = checkNotNull(client, "client");
      this.retryer = checkNotNull(retryer, "retryer");
      this.requestInterceptors = checkNotNull(requestInterceptors, "requestInterceptors");
      this.logger = checkNotNull(logger, "logger");
      this.logLevel = checkNotNull(logLevel, "logLevel");
      this.dismiss404 = dismiss404;
      this.closeAfterDecode = closeAfterDecode;
      this.propagationPolicy = propagationPolicy;
      this.forceDecoding = forceDecoding;
    }

    public MethodHandler create(Target<?> target,
                                MethodMetadata md,
                                RequestTemplate.Factory buildTemplateFromArgs,
                                Options options,
                                Decoder decoder,
                                ErrorDecoder errorDecoder) {
      return new SynchronousMethodHandler(target, client, retryer, requestInterceptors, logger,
          logLevel, md, buildTemplateFromArgs, options, decoder,
          errorDecoder, dismiss404, closeAfterDecode, propagationPolicy, forceDecoding);
    }
  }
}
