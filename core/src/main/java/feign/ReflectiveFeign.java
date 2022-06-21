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

import static feign.Util.checkArgument;
import static feign.Util.checkNotNull;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.Map.Entry;
import feign.InvocationHandlerFactory.MethodHandler;
import feign.Param.Expander;
import feign.Request.Options;
import feign.codec.*;
import feign.template.UriUtils;

/**
 * Feign 的子类实现，使用反射创建代理对象
 *
 * @author ZhangJun
 * @create 2022/6/18 17:14
 */
public class ReflectiveFeign extends Feign {

  private final ParseHandlersByName targetToHandlersByName;
  private final InvocationHandlerFactory factory;
  private final QueryMapEncoder queryMapEncoder;


  /**
   * 构造函数
   *
   * @param targetToHandlersByName MethodHandler 解析器
   * @param factory  方法调用处理工厂，拦截代理对象的方法调用，以进行方法增强
   * @param queryMapEncoder 编码器，将 object 转为 map
   */
  ReflectiveFeign(ParseHandlersByName targetToHandlersByName, InvocationHandlerFactory factory,
      QueryMapEncoder queryMapEncoder) {
    this.targetToHandlersByName = targetToHandlersByName;
    this.factory = factory;
    this.queryMapEncoder = queryMapEncoder;
  }

  /**
   * 创建接口的代理对象实例
   * <p>
   *
   * creates an api binding to the {@code target}. As this invokes reflection, care should be taken
   * to cache the result.
   */
  @SuppressWarnings("unchecked")
  @Override
  public <T> T newInstance(Target<T> target) {
    // 生成接口中所声明方法对应的处理器，并获取方法的名称与其处理器的映射关系
    Map<String, MethodHandler> nameToHandler = targetToHandlersByName.apply(target);
    // 原方法与方法处理器的最终映射关系
    Map<Method, MethodHandler> methodToHandler = new LinkedHashMap<Method, MethodHandler>();
    // 所有 default 方法的处理器
    List<DefaultMethodHandler> defaultMethodHandlers = new LinkedList<DefaultMethodHandler>();

    // 构建原方法与处理器的最终映射关系
    for (Method method : target.type().getMethods()) {
      if (method.getDeclaringClass() == Object.class) {
        continue;
      }
      // 方法作用域为 default
      else if (Util.isDefault(method)) {
        // 创建 DefaultMethodHandler，用来覆盖工厂创建的 MethodHandler
        DefaultMethodHandler handler = new DefaultMethodHandler(method);
        // 将方法处理器加入到 defaultMethodHandlers
        defaultMethodHandlers.add(handler);
        // 使用 DefaultMethodHandler 作为该方法的处理器
        methodToHandler.put(method, handler);
      }
      // 方法的作用域不是 default，使用 MethodHandlerFactory 生成的 MethodHandler 作为方法处理器
      else {
        methodToHandler.put(method, nameToHandler.get(Feign.configKey(target.type(), method)));
      }
    }

    // 生成 InvocationHandler，用于处理对象的方法调用，起到分发作用
    InvocationHandler handler = factory.create(target, methodToHandler);

    // 使用反射创建对象
    T proxy = (T) Proxy.newProxyInstance(target.type().getClassLoader(), new Class<?>[] {target.type()}, handler);

    // 将 default 类型方法的处理器绑定到代理对象上，它实际上就是把接口的原方法绑定到了代理对象上
    // 也就是说接口中 default 类型的方法不会被代理而是直接调用
    for (DefaultMethodHandler defaultMethodHandler : defaultMethodHandlers) {
      defaultMethodHandler.bindTo(proxy);
    }

    // 返回代理对象
    return proxy;
  }

  static class FeignInvocationHandler implements InvocationHandler {

    private final Target target;
    private final Map<Method, MethodHandler> dispatch;

    FeignInvocationHandler(Target target, Map<Method, MethodHandler> dispatch) {
      this.target = checkNotNull(target, "target");
      this.dispatch = checkNotNull(dispatch, "dispatch for %s", target);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if ("equals".equals(method.getName())) {
        try {
          Object otherHandler =
              args.length > 0 && args[0] != null ? Proxy.getInvocationHandler(args[0]) : null;
          return equals(otherHandler);
        } catch (IllegalArgumentException e) {
          return false;
        }
      } else if ("hashCode".equals(method.getName())) {
        return hashCode();
      } else if ("toString".equals(method.getName())) {
        return toString();
      }

      return dispatch.get(method).invoke(args);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof FeignInvocationHandler) {
        FeignInvocationHandler other = (FeignInvocationHandler) obj;
        return target.equals(other.target);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return target.hashCode();
    }

    @Override
    public String toString() {
      return target.toString();
    }
  }

  /**
   * 接口方法的解析器
   * 根据接口中声明的方法创建对应的方法处理器并生成方法名(准确的说是configKey)与处理器的映射关系
   */
  static final class ParseHandlersByName {

    private final Contract contract;
    private final Options options;
    private final Encoder encoder;
    private final Decoder decoder;
    private final ErrorDecoder errorDecoder;
    private final QueryMapEncoder queryMapEncoder;
    private final SynchronousMethodHandler.Factory factory;

    ParseHandlersByName(
        Contract contract,
        Options options,
        Encoder encoder,
        Decoder decoder,
        QueryMapEncoder queryMapEncoder,
        ErrorDecoder errorDecoder,
        SynchronousMethodHandler.Factory factory) {
      this.contract = contract;
      this.options = options;
      this.factory = factory;
      this.errorDecoder = errorDecoder;
      this.queryMapEncoder = queryMapEncoder;
      this.encoder = checkNotNull(encoder, "encoder");
      this.decoder = checkNotNull(decoder, "decoder");
    }

    /**
     * 生成接口的方法处理器并返回原方法名与处理器的映射关系
     */
    public Map<String, MethodHandler> apply(Target target) {
      // 该方法的元数据，通过 contract 获取，可以自定义 contract
      // spring-cloud-openfeign 就自定义了 SpringMvcContract 来适配 MVC 注解
      List<MethodMetadata> metadata = contract.parseAndValidateMetadata(target.type());
      Map<String, MethodHandler> result = new LinkedHashMap<String, MethodHandler>();
      // 遍历所有方法，生成方法处理器
      for (MethodMetadata md : metadata) {
        // 创建 RequestTemplate 的构造器，用于后续创建 RequestTemplate
        BuildTemplateByResolvingArgs buildTemplate;
        if (!md.formParams().isEmpty() && md.template().bodyTemplate() == null) {
          buildTemplate = new BuildFormEncodedTemplateFromArgs(md, encoder, queryMapEncoder, target);
        }
        else if (md.bodyIndex() != null || md.alwaysEncodeBody()) {
          buildTemplate = new BuildEncodedTemplateFromArgs(md, encoder, queryMapEncoder, target);
        }
        else {
          buildTemplate = new BuildTemplateByResolvingArgs(md, queryMapEncoder, target);
        }

        // 一些需要被忽略的方法如果使用 Feign 调用则直接抛异常，比如被 Spring 的 @ExceptionHandler 注解修饰的方法
        if (md.isIgnored()) {
          result.put(md.configKey(), args -> {
            throw new IllegalStateException(md.configKey() + " is not a method handled by feign");
          });
        }
        // 其它方法正常创建对应的处理器
        else {
          // 使用 MethodHandlerFactory 创建 MethodHandler，并加入到映射关系的 map 中
          result.put(md.configKey(), factory.create(target, md, buildTemplate, options, decoder, errorDecoder));
        }
      }

      return result;
    }
  }

  /**
   * RequestTemplate 的构造器，这里是根据方法中的参数来构造。
   * 该类包含了方法的元数据、接口的 Target 信息等，可以据此构建 HTTP 请求
   *
   * @author ZhangJun
   * @create 2022/6/18 18:21
   */
  private static class BuildTemplateByResolvingArgs implements RequestTemplate.Factory {

    private final QueryMapEncoder queryMapEncoder;

    protected final MethodMetadata metadata;
    protected final Target<?> target;
    private final Map<Integer, Expander> indexToExpander = new LinkedHashMap<Integer, Expander>();

    private BuildTemplateByResolvingArgs(MethodMetadata metadata, QueryMapEncoder queryMapEncoder, Target target) {
      this.metadata = metadata;
      this.target = target;
      this.queryMapEncoder = queryMapEncoder;
      if (metadata.indexToExpander() != null) {
        indexToExpander.putAll(metadata.indexToExpander());
        return;
      }
      if (metadata.indexToExpanderClass().isEmpty()) {
        return;
      }
      for (Entry<Integer, Class<? extends Expander>> indexToExpanderClass : metadata
          .indexToExpanderClass().entrySet()) {
        try {
          indexToExpander
              .put(indexToExpanderClass.getKey(), indexToExpanderClass.getValue().newInstance());
        } catch (InstantiationException e) {
          throw new IllegalStateException(e);
        } catch (IllegalAccessException e) {
          throw new IllegalStateException(e);
        }
      }
    }

    /**
     * 创建 RequestTemplate，方便后续以此构建 HTTP 请求
     *
     * @param argv 调用代理对象方法时传入的参数
     * @return RequestTemplate
     */
    @Override
    public RequestTemplate create(Object[] argv) {
      // 根据方法的元数据中已存在的 RequestTemplate 构建一个临时的 RequestTemplate
      RequestTemplate mutable = RequestTemplate.from(metadata.template());
      mutable.feignTarget(target);

      // 填充 url？
      if (metadata.urlIndex() != null) {
        int urlIndex = metadata.urlIndex();
        checkArgument(argv[urlIndex] != null, "URI parameter %s was null", urlIndex);
        mutable.target(String.valueOf(argv[urlIndex]));
      }

      // 构建方法参数名和参数值的映射关系
      Map<String, Object> varBuilder = new LinkedHashMap<String, Object>();
      for (Entry<Integer, Collection<String>> entry : metadata.indexToName().entrySet()) {
        int i = entry.getKey();
        Object value = argv[entry.getKey()];
        if (value != null) { // Null values are skipped.
          if (indexToExpander.containsKey(i)) {
            value = expandElements(indexToExpander.get(i), value);
          }
          for (String name : entry.getValue()) {
            varBuilder.put(name, value);
          }
        }
      }

      // 根据上面的参数、临时模板、参数名和参数值的映射关系，构建出最终的 RequestTemplate
      // 这里会构建出最终的 uri，并设置请求头、body
      RequestTemplate template = resolve(argv, mutable, varBuilder);

      // 填充 get 参数 todo ？上面填充过了
      if (metadata.queryMapIndex() != null) {
        // add query map parameters after initial resolve so that they take
        // precedence over any predefined values
        Object value = argv[metadata.queryMapIndex()];
        Map<String, Object> queryMap = toQueryMap(value);
        template = addQueryMapQueryParameters(queryMap, template);
      }

      // 填充请求头 todo ?
      if (metadata.headerMapIndex() != null) {
        // add header map parameters for a resolution of the user pojo object
        Object value = argv[metadata.headerMapIndex()];
        Map<String, Object> headerMap = toQueryMap(value);
        template = addHeaderMapHeaders(headerMap, template);
      }

      return template;
    }

    private Map<String, Object> toQueryMap(Object value) {
      if (value instanceof Map) {
        return (Map<String, Object>) value;
      }
      try {
        return queryMapEncoder.encode(value);
      } catch (EncodeException e) {
        throw new IllegalStateException(e);
      }
    }

    private Object expandElements(Expander expander, Object value) {
      if (value instanceof Iterable) {
        return expandIterable(expander, (Iterable) value);
      }
      return expander.expand(value);
    }

    private List<String> expandIterable(Expander expander, Iterable value) {
      List<String> values = new ArrayList<String>();
      for (Object element : value) {
        if (element != null) {
          values.add(expander.expand(element));
        }
      }
      return values;
    }

    @SuppressWarnings("unchecked")
    private RequestTemplate addHeaderMapHeaders(Map<String, Object> headerMap,
                                                RequestTemplate mutable) {
      for (Entry<String, Object> currEntry : headerMap.entrySet()) {
        Collection<String> values = new ArrayList<String>();

        Object currValue = currEntry.getValue();
        if (currValue instanceof Iterable<?>) {
          Iterator<?> iter = ((Iterable<?>) currValue).iterator();
          while (iter.hasNext()) {
            Object nextObject = iter.next();
            values.add(nextObject == null ? null : nextObject.toString());
          }
        } else {
          values.add(currValue == null ? null : currValue.toString());
        }

        mutable.header(currEntry.getKey(), values);
      }
      return mutable;
    }

    @SuppressWarnings("unchecked")
    private RequestTemplate addQueryMapQueryParameters(Map<String, Object> queryMap,
                                                       RequestTemplate mutable) {
      for (Entry<String, Object> currEntry : queryMap.entrySet()) {
        Collection<String> values = new ArrayList<String>();

        Object currValue = currEntry.getValue();
        if (currValue instanceof Iterable<?>) {
          Iterator<?> iter = ((Iterable<?>) currValue).iterator();
          while (iter.hasNext()) {
            Object nextObject = iter.next();
            values.add(nextObject == null ? null : UriUtils.encode(nextObject.toString()));
          }
        } else if (currValue instanceof Object[]) {
          for (Object value : (Object[]) currValue) {
            values.add(value == null ? null : UriUtils.encode(value.toString()));
          }
        } else {
          if (currValue != null) {
            values.add(UriUtils.encode(currValue.toString()));
          }
        }

        if (values.size() > 0) {
          mutable.query(UriUtils.encode(currEntry.getKey()), values);
        }
      }
      return mutable;
    }

    protected RequestTemplate resolve(Object[] argv,
                                      RequestTemplate mutable,
                                      Map<String, Object> variables) {
      return mutable.resolve(variables);
    }
  }

  private static class BuildFormEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {

    private final Encoder encoder;

    private BuildFormEncodedTemplateFromArgs(MethodMetadata metadata, Encoder encoder,
        QueryMapEncoder queryMapEncoder, Target target) {
      super(metadata, queryMapEncoder, target);
      this.encoder = encoder;
    }

    @Override
    protected RequestTemplate resolve(Object[] argv,
                                      RequestTemplate mutable,
                                      Map<String, Object> variables) {
      Map<String, Object> formVariables = new LinkedHashMap<String, Object>();
      for (Entry<String, Object> entry : variables.entrySet()) {
        if (metadata.formParams().contains(entry.getKey())) {
          formVariables.put(entry.getKey(), entry.getValue());
        }
      }
      try {
        encoder.encode(formVariables, Encoder.MAP_STRING_WILDCARD, mutable);
      } catch (EncodeException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new EncodeException(e.getMessage(), e);
      }
      return super.resolve(argv, mutable, variables);
    }
  }

  private static class BuildEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {

    private final Encoder encoder;

    private BuildEncodedTemplateFromArgs(MethodMetadata metadata, Encoder encoder,
        QueryMapEncoder queryMapEncoder, Target target) {
      super(metadata, queryMapEncoder, target);
      this.encoder = encoder;
    }

    @Override
    protected RequestTemplate resolve(Object[] argv,
                                      RequestTemplate mutable,
                                      Map<String, Object> variables) {

      boolean alwaysEncodeBody = mutable.methodMetadata().alwaysEncodeBody();

      Object body = null;
      if (!alwaysEncodeBody) {
        body = argv[metadata.bodyIndex()];
        checkArgument(body != null, "Body parameter %s was null", metadata.bodyIndex());
      }

      try {
        if (alwaysEncodeBody) {
          body = argv == null ? new Object[0] : argv;
          encoder.encode(body, Object[].class, mutable);
        } else {
          encoder.encode(body, metadata.bodyType(), mutable);
        }
      } catch (EncodeException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new EncodeException(e.getMessage(), e);
      }
      return super.resolve(argv, mutable, variables);
    }
  }
}
