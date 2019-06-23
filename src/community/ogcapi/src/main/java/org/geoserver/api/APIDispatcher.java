/*
 *  (c) 2019 Open Source Geospatial Foundation - all rights reserved
 *  This code is licensed under the GPL 2.0 license, available at the root
 *  application directory.
 */

package org.geoserver.api;

import static org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.geoserver.config.GeoServer;
import org.geoserver.ows.Dispatcher;
import org.geoserver.ows.DispatcherCallback;
import org.geoserver.ows.Request;
import org.geoserver.ows.util.KvpMap;
import org.geoserver.ows.util.KvpUtils;
import org.geoserver.platform.*;
import org.geotools.util.Version;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextStartedEvent;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.GenericHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.HandlerMethodReturnValueHandlerComposite;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.handler.DispatcherServletWebRequest;
import org.springframework.web.servlet.mvc.AbstractController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor;
import org.springframework.web.servlet.support.RequestContextUtils;
import org.springframework.web.util.WebUtils;

public class APIDispatcher extends AbstractController
        implements ApplicationListener<ContextStartedEvent> {

    static final String RESPONSE_OBJECT = "ResponseObject";

    public static final String ROOT_PATH = "ogc";

    static final Charset UTF8 = Charset.forName("UTF-8");

    static final Logger LOGGER = org.geotools.util.logging.Logging.getLogger("org.geoserver.api");

    // SHARE
    /** list of callbacks */
    protected List<DispatcherCallback> callbacks = Collections.EMPTY_LIST;

    protected RequestMappingHandlerMapping mappingHandler;

    protected RequestMappingHandlerAdapter handlerAdapter;
    protected HandlerMethodReturnValueHandlerComposite returnValueHandlers;
    protected APIContentNegotiationManager contentNegotiationManager =
            new APIContentNegotiationManager();
    private List<HttpMessageConverter<?>> messageConverters;

    // SHARE
    @Override
    protected void initApplicationContext(ApplicationContext context) {
        // load life cycle callbacks
        callbacks = GeoServerExtensions.extensions(DispatcherCallback.class, context);

        this.mappingHandler =
                new RequestMappingHandlerMapping() {
                    @Override
                    protected boolean isHandler(Class<?> beanType) {
                        return hasAnnotation(beanType, APIService.class);
                    }
                };
        this.mappingHandler.setApplicationContext(context);
        this.mappingHandler.afterPropertiesSet();
        // do we really want this? The REST API uses it though
        this.mappingHandler.getUrlPathHelper().setAlwaysUseFullPath(true);

        // create the one handler adapter we need similar to how DispatcherServlet does it
        // but with a special implementation that supports callbacks for the operation
        APIConfigurationSupport configurationSupport =
                context.getAutowireCapableBeanFactory().createBean(APIConfigurationSupport.class);
        configurationSupport.setCallbacks(callbacks);
        handlerAdapter = configurationSupport.requestMappingHandlerAdapter();
        handlerAdapter.setApplicationContext(context);
        handlerAdapter.afterPropertiesSet();
        // force json as the first choice
        handlerAdapter.getMessageConverters().add(0, new MappingJackson2HttpMessageConverter());
        handlerAdapter.getMessageConverters().add(0, new MappingJackson2YAMLMessageConverter());
        // add all registered converters before the Spring ones too
        List<HttpMessageConverter> extensionConverters =
                GeoServerExtensions.extensions(HttpMessageConverter.class);
        addToListBackwards(extensionConverters, handlerAdapter.getMessageConverters());
        this.messageConverters = handlerAdapter.getMessageConverters();

        // add custom argument resolvers
        List<HandlerMethodArgumentResolver> pluginResolvers =
                GeoServerExtensions.extensions(HandlerMethodArgumentResolver.class);
        List<HandlerMethodArgumentResolver> adapterResolvers =
                new ArrayList<>(handlerAdapter.getArgumentResolvers());
        addToListBackwards(pluginResolvers, adapterResolvers);
        handlerAdapter.setArgumentResolvers(adapterResolvers);

        // default treatment of "f" parameter and headers, defaulting to JSON if nothing else has
        // been provided
        List<HandlerMethodReturnValueHandler> returnValueHandlers =
                handlerAdapter
                        .getReturnValueHandlers()
                        .stream()
                        .map(
                                f -> {
                                    if (f instanceof RequestResponseBodyMethodProcessor) {
                                        // replace with custom version that can do HTML output based
                                        // on method annotations and does generic OGC API content
                                        // negotiation
                                        return new APIBodyMethodProcessor(
                                                handlerAdapter.getMessageConverters(),
                                                GeoServerExtensions.bean(
                                                        GeoServerResourceLoader.class),
                                                GeoServerExtensions.bean(GeoServer.class),
                                                callbacks);
                                    } else {
                                        return f;
                                    }
                                })
                        .collect(Collectors.toList());

        // split handling of response  in two to respect the Dispatcher Operation/Response
        // architecture
        this.returnValueHandlers = new HandlerMethodReturnValueHandlerComposite();
        this.returnValueHandlers.addHandlers(returnValueHandlers);
        handlerAdapter.setReturnValueHandlers(
                Arrays.asList(
                        new HandlerMethodReturnValueHandler() {
                            @Override
                            public boolean supportsReturnType(MethodParameter returnType) {
                                return true;
                            }

                            @Override
                            public void handleReturnValue(
                                    Object returnValue,
                                    MethodParameter returnType,
                                    ModelAndViewContainer mavContainer,
                                    NativeWebRequest webRequest)
                                    throws Exception {
                                mavContainer.getModel().put(RESPONSE_OBJECT, returnValue);
                            }
                        }));
    }

    private void addToListBackwards(List source, List target) {
        // add them in reverse order to the head, so that they will have the same order as extension
        // priority commands
        ListIterator arIterator = source.listIterator(source.size());
        while (arIterator.hasPrevious()) {
            target.add(0, arIterator.previous());
        }
    }

    @Override
    public void onApplicationEvent(ContextStartedEvent event) {}

    // SHARE
    protected void preprocessRequest(HttpServletRequest request) throws Exception {
        // set the charset
        Charset charSet = null;

        // TODO: make this server settable
        charSet = UTF8;
        if (request.getCharacterEncoding() != null)
            try {
                charSet = Charset.forName(request.getCharacterEncoding());
            } catch (Exception e) {
                // ok, we tried...
            }

        request.setCharacterEncoding(charSet.name());
    }

    // SHARE? SLIGHT CHANGES
    @Override
    protected ModelAndView handleRequestInternal(
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws Exception {

        preprocessRequest(httpRequest);

        // create a new request instance
        Request dr = new Request();

        // set request / response
        dr.setHttpRequest(httpRequest);
        dr.setHttpResponse(httpResponse);
        dr.setGet("GET".equalsIgnoreCase(httpRequest.getMethod()));

        try {
            // initialize the request and allow callbacks to override it
            dr = init(dr);

            // store it in the thread local
            Dispatcher.REQUEST.set(dr);
            // add a thread local with info on formats, base urls, and the like
            RequestInfo requestInfo = new RequestInfo(httpRequest, this);
            requestInfo.setRequestedMediaTypes(
                    contentNegotiationManager.resolveMediaTypes(
                            new ServletWebRequest(dr.getHttpRequest())));
            RequestInfo.set(requestInfo);

            // lookup the handler adapter (same as service and operation)
            HandlerMethod handler = getHandlerMethod(httpRequest, dr);
            dispatchService(dr, handler);

            // this is actually "execute"
            ModelAndView mav =
                    handlerAdapter.handle(dr.getHttpRequest(), dr.getHttpResponse(), handler);

            ModelAndViewContainer mavContainer = new ModelAndViewContainer();
            mavContainer.addAllAttributes(
                    RequestContextUtils.getInputFlashMap(dr.getHttpRequest()));

            // and this is response handling
            Object returnValue = mav.getModel().get(RESPONSE_OBJECT);
            returnValue = fireOperationExecutedCallback(dr, dr.getOperation(), returnValue);

            returnValueHandlers.handleReturnValue(
                    returnValue,
                    new ReturnValueMethodParameter(handler.getMethod(), returnValue),
                    mavContainer,
                    new DispatcherServletWebRequest(dr.getHttpRequest(), dr.getHttpResponse()));

            // find the service
            // service = service(request);
            //
            //            // throw any outstanding errors
            //            if (request.getError() != null) {
            //                throw request.getError();
            //            }
            //
            //            // dispatch the operation
            //            Operation operation = dispatch(request, service);
            //            request.setOperation(operation);
            //
            //            // execute it
            //            Object result = execute(request, operation);
            //
            //            // write the response
            //            if (result != null) {
            //                response(result, request, operation);
            //            }
        } catch (Throwable t) {
            // make Spring security exceptions flow so that exception transformer filter can handle
            // them
            if (isSecurityException(t)) throw (Exception) t;
            exception(t, dr);
        } finally {
            fireFinishedCallback(dr);
            Dispatcher.REQUEST.remove();
        }

        return null;
    }

    private void dispatchService(Request dr, HandlerMethod handler) {
        // get the annotations and set service, version and request
        APIService annotation = handler.getBeanType().getAnnotation(APIService.class);
        dr.setService(annotation.service());
        dr.setVersion(annotation.version());
        RequestMapping requestMapping = handler.getMethod().getAnnotation(RequestMapping.class);
        if (requestMapping != null) {
            dr.setRequest(requestMapping.name());
        }
        // if not request name was found fall back on the method name
        if (dr.getRequest() == null) {
            dr.setRequest(handler.getMethod().getName());
        }

        // comply with DispatcherCallback and fire a service dispatched callback
        Service service =
                new Service(
                        annotation.service(),
                        handler.getBean(),
                        new Version(annotation.service()),
                        Collections.emptyList());
        dr.setServiceDescriptor(service);
        service = fireServiceDispatchedCallback(dr, service);
        // replace in case callbacks have replaced it
        dr.setServiceDescriptor(service);
    }

    private Service getService(HandlerMethod handler) {
        APIService annotation = handler.getBeanType().getAnnotation(APIService.class);
        return new Service(
                annotation.service(),
                handler.getBean(),
                new Version(annotation.service()),
                Collections.emptyList());
    }

    private HandlerMethod getHandlerMethod(HttpServletRequest httpRequest, Request dr)
            throws Exception {
        HandlerExecutionChain chain = mappingHandler.getHandler(dr.getHttpRequest());
        if (chain == null) {
            String msg =
                    "No mapping for " + httpRequest.getMethod() + " " + getRequestUri(httpRequest);
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning(msg);
            }
            throw new APIException(msg, HttpStatus.NOT_FOUND);
        }
        Object handler = chain.getHandler();
        if (!handlerAdapter.supports(handler)) {
            String msg =
                    "Mapping for "
                            + httpRequest.getMethod()
                            + " "
                            + getRequestUri(httpRequest)
                            + " found but it's not supported by the HandlerAdapter. Check for mis-setup of service beans";
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning(msg);
            }
            throw new APIException(msg, HttpStatus.NOT_FOUND);
        }
        return (HandlerMethod) handler;
    }

    private void exception(Throwable t, Request request) throws IOException {
        HttpServletResponse response = request.getHttpResponse();
        LOGGER.log(Level.SEVERE, "Failed to dispatch API request", t);

        if (t instanceof APIException) {
            APIException exception = (APIException) t;
            response.setContentType(exception.getMediaType().toString());
            response.sendError(exception.getStatus().value(), exception.getMessage());
        } else {
            response.setContentType(MediaType.TEXT_PLAIN_VALUE);
            response.sendError(
                    HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    "Internal server error, check logs for details");
        }
    }

    Request init(Request request) throws ServiceException, IOException {
        HttpServletRequest httpRequest = request.getHttpRequest();

        // parse the request path into two components. (1) the 'path' which
        // is the string after the last '/', and the 'context' which is the
        // string before the last '/'
        String ctxPath = request.getHttpRequest().getContextPath();
        String reqPath = request.getHttpRequest().getRequestURI();
        reqPath = reqPath.substring(ctxPath.length());

        // strip off leading and trailing slashes
        if (reqPath.startsWith("/")) {
            reqPath = reqPath.substring(1, reqPath.length());
        }

        if (reqPath.endsWith("/")) {
            reqPath = reqPath.substring(0, reqPath.length() - 1);
        }

        String context = reqPath;
        String path = null;
        int index = context.lastIndexOf('/');
        if (index != -1) {
            path = context.substring(index + 1);
            context = context.substring(0, index);
        } else {
            path = reqPath;
            context = null;
        }
        request.setContext(context);
        request.setPath(path);

        // TODO: MVC will handle these from the request, do we need to wrap the HTTP request?
        // most likely...

        // unparsed kvp set
        Map kvp = request.getHttpRequest().getParameterMap();

        if (kvp == null || kvp.isEmpty()) {
            request.setKvp(new HashMap());
            request.setRawKvp(new HashMap());
        } else {
            // track parsed kvp and unparsd
            Map parsedKvp = KvpUtils.normalize(kvp);
            Map rawKvp = new KvpMap(parsedKvp);

            request.setKvp(parsedKvp);
            request.setRawKvp(rawKvp);
        }

        return fireInitCallback(request);
    }

    // SHARE
    Request fireInitCallback(Request req) {
        for (DispatcherCallback cb : callbacks) {
            Request r = cb.init(req);
            req = r != null ? r : req;
        }
        return req;
    }

    // SHARE (or move to a callback handler/list class of sort?)
    void fireFinishedCallback(Request req) {
        for (DispatcherCallback cb : callbacks) {
            try {
                cb.finished(req);
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "Error firing finished callback for " + cb.getClass(), t);
            }
        }
    }

    /**
     * Examines a {@link Throwable} object and returns true if it represents a security exception.
     *
     * @param t Throwable
     * @return true if t is a security exception
     */
    // SHARE
    protected static boolean isSecurityException(Throwable t) {
        return t != null
                && t.getClass().getPackage().getName().startsWith("org.springframework.security");
    }

    // SHARE
    Service fireServiceDispatchedCallback(Request req, Service service) {
        for (DispatcherCallback cb : callbacks) {
            Service s = cb.serviceDispatched(req, service);
            service = s != null ? s : service;
        }
        return service;
    }

    // SHARE
    Object fireOperationExecutedCallback(Request req, Operation op, Object result) {
        for (DispatcherCallback cb : callbacks) {
            Object r = cb.operationExecuted(req, op, result);
            result = r != null ? r : result;
        }
        return result;
    }

    /**
     * This comes from {@link org.springframework.web.servlet.DispatcherServlet}, it's private and
     * thus not reusable
     */
    private static String getRequestUri(HttpServletRequest request) {
        String uri = (String) request.getAttribute(WebUtils.INCLUDE_REQUEST_URI_ATTRIBUTE);
        if (uri == null) {
            uri = request.getRequestURI();
        }
        return uri;
    }

    public List<HttpMessageConverter<?>> getConverters() {
        return messageConverters;
    }

    public Collection<MediaType> getProducibleMediaTypes(Class<?> responseType, boolean addHTML) {
        List<MediaType> result = new ArrayList<>();
        for (HttpMessageConverter<?> converter : this.messageConverters) {
            if (converter instanceof GenericHttpMessageConverter) {
                if (((GenericHttpMessageConverter<?>) converter)
                        .canWrite(responseType, responseType, null)) {
                    result.addAll(converter.getSupportedMediaTypes());
                }
            } else if (converter.canWrite(responseType, null)) {
                result.addAll(converter.getSupportedMediaTypes());
            }
        }
        if (addHTML) {
            result.add(MediaType.TEXT_HTML);
        }

        return result.stream()
                .filter(mt -> mt.isConcrete())
                .distinct()
                .collect(Collectors.toList());
    }
}
