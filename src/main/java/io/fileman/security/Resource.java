package io.fileman.security;

import org.dom4j.Element;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 资源
 *
 * @author 杨昌沛 646742615@qq.com
 * 2018/9/25
 */
public class Resource extends Node {
    private final String name;
    private final List<String> methods;
    private final List<String> paths;

    public Resource(Element element) {
        super(element);

        this.name = element.attributeValue("name");

        String method = element.attributeValue("method");
        this.methods = method == null || method.isEmpty() || method.equals("*")
                ? Collections.<String>emptyList()
                : Arrays.asList(method.toUpperCase().split("\\s*,\\s*"));

        String path = element.attributeValue("path");
        this.paths = path == null
                ? Collections.<String>emptyList()
                : Arrays.asList(path.split("\\s*,\\s*"));
    }

    @Override
    public boolean matches(String method, String path) {
        return (methods.isEmpty() || methods.contains(method.toUpperCase())) && paths.contains(path);
    }

    public String getName() {
        return name;
    }

    public List<String> getMethods() {
        return methods;
    }

    public List<String> getPaths() {
        return paths;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Resource resource = (Resource) o;

        return name != null ? name.equals(resource.name) : resource.name == null;
    }

    @Override
    public int hashCode() {
        return name != null ? name.hashCode() : 0;
    }

    public String toString() {
        return name;
    }
}