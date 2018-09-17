package io.fileman;

import io.detector.Filter;
import io.detector.FilterChain;
import io.detector.Resource;
import io.detector.SimpleDetector;
import io.fileman.formatter.HtmlFormatter;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 文件管理器WEB集成
 *
 * @author 杨昌沛 646742615@qq.com
 * 2018/9/14
 */
public class FilemanWebSupport {
    protected Configuration configuration;
    protected String root;
    protected Synthesizer<Converter> synthesizer;
    protected Formatter formatter;
    protected int buffer;
    protected List<Converter> converters = new ArrayList<>();
    protected Map<String, Extractor> extractors = new LinkedHashMap<>();

    protected void init(Configuration configuration) throws ServletException {
        try {
            this.configuration = configuration;
            root = Filemans.ifBlank(configuration.valueOf("root"), System.getProperty("user.dir"));
            synthesizer = Filemans.newInstance(Filemans.ifBlank(configuration.valueOf("synthesizer"), RenderSynthesizer.class.getName()));
            formatter = Filemans.newInstance(Filemans.ifBlank(configuration.valueOf("formatter"), HtmlFormatter.class.getName()));
            buffer = Integer.valueOf(Filemans.ifBlank(configuration.valueOf("buffer"), "" + 1024 * 8));
            initConverters(configuration);
            initExtractors(configuration);
        } catch (IOException e) {
            throw new ServletException(e);
        }
    }

    private void initConverters(Configuration configuration) throws IOException, ServletException {
        String fields = configuration.valueOf("fields");
        String[] columns = fields.split("[,\\s\r\n]+");
        Collection<Resource> resources = SimpleDetector.Builder
                .scan("fileman")
                .includeJar()
                .recursively()
                .build()
                .detect(new ConverterConfigFilter());
        Properties properties = new Properties();
        for (Resource resource : resources) {
            InputStream in = resource.getInputStream();
            properties.load(in);
        }
        for (String column : columns) {
            String className = properties.getProperty(column);
            if (className == null) className = column;
            try {
                Class<? extends Converter> clazz = Class.forName(className).asSubclass(Converter.class);
                Converter converter = clazz.newInstance();
                converters.add(converter);
            } catch (Exception e) {
                throw new ServletException("unknown field " + column);
            }
        }
    }

    private void initExtractors(Configuration configuration) throws IOException, ServletException {
        Collection<Resource> resources = SimpleDetector.Builder
                .scan("fileman")
                .includeJar()
                .recursively()
                .build()
                .detect(new ExtractorConfigFilter());
        Properties properties = new Properties();
        for (Resource resource : resources) {
            InputStream in = resource.getInputStream();
            properties.load(in);
        }
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            try {
                String unit = (String) entry.getKey();
                String className = (String) entry.getValue();
                Class<? extends Extractor> clazz = Class.forName(className).asSubclass(Extractor.class);
                Extractor extractor = clazz.newInstance();
                extractors.put(unit, extractor);
            } catch (Exception e) {
                throw new ServletException(e);
            }
        }
    }

    protected void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        String method = request.getMethod().toUpperCase();
        switch (method) {
            case "GET":
                get(request, response);
                break;
            case "POST":
                post(request, response);
                break;
            case "PUT":
                put(request, response);
                break;
            case "DELETE":
                delete(request, response);
                break;
            case "OPTIONS":
                options(request, response);
                break;
            case "TRACE":
                trace(request, response);
                break;
            default:
                extend(method, request, response);
                break;
        }
    }

    protected void get(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String requestPath = request.getRequestURI();
        String contextPath = request.getContextPath();
        String servletPath = request.getServletPath();
        String filemanPath = requestPath.substring(contextPath.length() + servletPath.length());
        while (filemanPath.endsWith("/")) filemanPath = filemanPath.substring(0, filemanPath.length() - 1);
        filemanPath = URLDecoder.decode(filemanPath, "UTF-8");
        File file = new File(root, filemanPath);
        // 文件不存在
        if (!file.exists()) {
            response.sendError(HttpURLConnection.HTTP_NOT_FOUND, "Not Found");
        }
        // 是目录
        else if (file.isDirectory()) {
            File root = new File(this.root);
            Fileman fileman = new Fileman();
            fileman.setUri(("/" + contextPath + "/" + servletPath + "/" + filemanPath).replaceAll("/+", "/"));
            fileman.setPath(filemanPath);
            fileman.setFolder(true);
            fileman.setChildren(new ArrayList<Fileman>());
            File[] files = file.listFiles();
            for (int i = 0; files != null && i < files.length; i++) {
                File sub = files[i];
                Fileman child = new Fileman();
                child.setUri(("/" + contextPath + "/" + servletPath + "/" + filemanPath + "/" + sub.getName()).replaceAll("/+", "/"));
                child.setPath(filemanPath + "/" + sub.getName());
                child.setFolder(sub.isDirectory());
                SynthesizeContext<Converter> context = new SynthesizeContext<>(root, configuration, request, response, converters);
                Map<String, Object> properties = synthesizer.synthesize(sub, context);
                child.setProperties(properties);
                fileman.getChildren().add(child);
            }
            SynthesizeContext<Converter> context = new SynthesizeContext<>(root, configuration, request, response, converters);
            Map<String, Object> properties = synthesizer.synthesize(file, context);
            fileman.setProperties(properties);

            formatter.format(fileman, new FormatContext(root, configuration, request, response));
        }
        // 是文件
        else if (file.isFile()) {
            String range = request.getHeader("Range");
            response.setHeader("Accept-Ranges", Filemans.join(extractors.keySet(), ", "));
            // 全部读取
            if (Filemans.isBlank(range)) {
                Path path = Paths.get(file.toURI());
                String contentType = Files.probeContentType(path);
                if (contentType == null) contentType = "application/octet-stream";
                response.setContentType(contentType);

                String name = file.getName();
                String contentDisposition = "attachment; filename=\"" + URLEncoder.encode(name, "UTF-8") + "\"";
                response.setHeader("Content-Disposition", contentDisposition);

                OutputStream out = response.getOutputStream();
                InputStream in = null;
                try {
                    in = new FileInputStream(file);
                    byte[] buf = new byte[buffer];
                    int len;
                    while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
                } finally {
                    Filemans.close(in);
                }
            }
            // 部分读取
            else {
                Range r = Range.valueOf(range);
                Extractor extractor = null;
                for (Extractor e : extractors.values()) if (e.supports(file, r)) extractor = e;
                if (extractor == null) response.sendError(HttpURLConnection.HTTP_NOT_IMPLEMENTED, "Not Implemented");
                else extractor.extract(file, r, new ExtractContext(new File(root), configuration, request, response));
            }
        }
        // 不认识
        else {
            response.sendError(HttpURLConnection.HTTP_NOT_FOUND, "Not Found");
        }
    }

    protected void post(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        String requestPath = request.getRequestURI();
        String contextPath = request.getContextPath();
        String servletPath = request.getServletPath();
        String filemanPath = requestPath.substring(contextPath.length() + servletPath.length());
        while (filemanPath.endsWith("/")) filemanPath = filemanPath.substring(0, filemanPath.length() - 1);
        filemanPath = URLDecoder.decode(filemanPath, "UTF-8");
        File file = new File(root, filemanPath);
        Collection<Part> parts = request.getParts();
        // 如果没有文件则返回
        if (parts.isEmpty()) {
            return;
        }
        // 如果文件夹不存在则创建
        if (!file.exists() && !file.mkdirs()) {
            response.sendError(HttpURLConnection.HTTP_BAD_REQUEST, "Bad Request");
            return;
        }
        // 如果当前路径不是一个文件夹则冲突
        if (!file.isDirectory()) {
            response.sendError(HttpURLConnection.HTTP_CONFLICT, "Conflict");
            return;
        }
        // 将文件写入该文件夹
        for (Part part : parts) {
            String disposition = part.getHeader("Content-Disposition");
            String[] segments = disposition.split("\\s*;\\s*");
            String filename = UUID.randomUUID().toString();
            for (String segment : segments) {
                String[] keyValue = segment.split("\\s*=\\s*");
                if (!"filename".equals(keyValue[0])) continue;
                filename = Filemans.unquote(keyValue[1]);
            }
            part.write(new File(file, filename).getPath());
        }
        response.setStatus(HttpURLConnection.HTTP_CREATED);
    }

    protected void put(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        String requestPath = request.getRequestURI();
        String contextPath = request.getContextPath();
        String servletPath = request.getServletPath();
        String filemanPath = requestPath.substring(contextPath.length() + servletPath.length());
        while (filemanPath.endsWith("/")) filemanPath = filemanPath.substring(0, filemanPath.length() - 1);
        filemanPath = URLDecoder.decode(filemanPath, "UTF-8");
        File file = new File(root, filemanPath);
        Collection<Part> parts = request.getParts();
        // 如果没有文件则返回
        if (parts.isEmpty()) {
            return;
        }
        // 如果所在目录不存在则创建
        File parent = file.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            response.sendError(HttpURLConnection.HTTP_BAD_REQUEST, "Bad Request");
            return;
        }
        // 如果当前路径是一个文件夹则冲突
        if (file.isDirectory()) {
            response.sendError(HttpURLConnection.HTTP_CONFLICT, "Conflict");
            return;
        }
        Part part = parts.iterator().next();
        part.write(file.getPath());
        response.setStatus(HttpURLConnection.HTTP_NO_CONTENT);
    }

    protected void delete(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String requestPath = request.getRequestURI();
        String contextPath = request.getContextPath();
        String servletPath = request.getServletPath();
        String filemanPath = requestPath.substring(contextPath.length() + servletPath.length());
        while (filemanPath.endsWith("/")) filemanPath = filemanPath.substring(0, filemanPath.length() - 1);
        filemanPath = URLDecoder.decode(filemanPath, "UTF-8");
        File file = new File(root, filemanPath);
        boolean deleted = Filemans.delete(file);
        if (deleted) response.setStatus(HttpURLConnection.HTTP_OK);
        else response.sendError(HttpURLConnection.HTTP_INTERNAL_ERROR);
    }

    protected void options(HttpServletRequest request, HttpServletResponse response) throws IOException {

    }

    protected void trace(HttpServletRequest request, HttpServletResponse response) throws IOException {

    }

    protected void extend(String method, HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.sendError(HttpURLConnection.HTTP_BAD_METHOD, "Method Not Allowed");
    }

    protected void destroy() {
        this.configuration = null;
        this.root = null;
        this.converters.clear();
        this.converters = null;
        this.synthesizer = null;
        this.formatter = null;
    }

    static class ConverterConfigFilter implements Filter {
        private final List<String> names = Arrays.asList(
                "converter.properties",
                "resolver.properties",
                "renderer.properties",
                "adapter.properties"
        );

        @Override
        public boolean accept(Resource resource, FilterChain chain) {
            return names.contains(resource.getName()) && chain.doNext(resource);
        }
    }

    static class ExtractorConfigFilter implements Filter {

        @Override
        public boolean accept(Resource resource, FilterChain chain) {
            return "extractor.properties".equals(resource.getName()) && chain.doNext(resource);
        }
    }
}
