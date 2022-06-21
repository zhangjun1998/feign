**简介**

这里是 [『香蕉大魔王』](https://github.com/zhangjun1998) 的 openfeign 源码解析，[原始项目地址](https://github.com/zhangjun1998/feign) 在这。
技术暂时还比较拙劣，可能有很多理解不到位或有出入的地方，所以源码注释仅供参考哈，如有错误还请指正，错误很多的话就别说了，请把一些优秀的博客地址贴给我，我爱学习。
这里解析的是我在 2022-06-16 那一天拉取的 master 分支。

暂时我只分析和 [香蕉大魔王的 spring-cloud-openfeign 源码解析](https://github.com/zhangjun1998/spring-cloud-openfeign) 项目相关的代码，大部分都在 `feign-core` 包下面，其它的有时间再看。

目前还没时间总结整体流程，有时间了我再用图文结合的方式把核心流程描述一遍，现在先 mark 一下。

**已看和待看的代码如下：**

+ [x] Feign：Feign 抽象类，用来提供创建 feignClient 的基本方法和操作
  + [x] Builder：内部类，Feign 构造器
+ [x] ReflectiveFeign：Feign 的 子类，使用反射创建代理对象
  + [x] ParseHandlersByName：内部类，创建接口的方法处理器并形成映射关系
+ [x] InvocationHandlerFactory：调用处理器工厂，可以生成调用处理器，用来控制代理对象反射方法的调用分发
  + [x] MethodHandler：内部接口，方法处理器
+ [x] DefaultMethodHandler：default 类型方法的处理器
+ [x] SynchronousMethodHandler：方法处理器的实现类，默认的方法处理器，该处理器是同步执行的
  + [x] SynchronousMethodHandler.Factory：SynchronousMethodHandler 工厂类
+ [x] RequestTemplate：Request 模板，用来构建 Request 对象，然后交给 HTTP 客户端执行 HTTP 请求
+ [ ] Client：HTTP 客户端接口，用于规定统一的 HTTP 调用规范，子类实现规范即可介入各 HTTP 客户端实现类
  + [ ] Default：默认 HTTP 客户端，使用 HttpURLConnection 执行 HTTP 请求
  + [ ] RibbonClient：Ribbon 客户端，可以支持负载均衡调用，它只对 HTTP 客户端做封装，本身不提供 HTTP 客户端功能，需要注入其它 HTTP 客户端
+ [ ] Contract：
+ [x] ...


**联系方式：**

+ 邮箱：zhangjun_java@163.com
+ 微信：rzy-zj

如要联系我请备注来意，不知道怎么备注的我提供个模板：「oh，你的 openfeign 源码解析的太好了，加个好友瞧一瞧」。好，祝大家技术进步，生活快乐。
