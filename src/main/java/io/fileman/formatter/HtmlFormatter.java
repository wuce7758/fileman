package io.fileman.formatter;

import io.fileman.Fileman;
import io.fileman.FormatContext;
import io.fileman.Formatter;
import io.fileman.Toolkit;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HTML格式化器
 *
 * @author 杨昌沛 646742615@qq.com
 * 2018/9/14
 */
public class HtmlFormatter implements Formatter {

    @Override
    public void format(Fileman fileman, FormatContext context) throws IOException {
        HttpServletRequest request = context.getRequest();
        HttpServletResponse response = context.getResponse();

        String requestPath = request.getRequestURI();
        // 如果路径后面没有 / 则重定向到以 / 结尾的路径上
        if (!requestPath.endsWith("/")) {
            response.sendRedirect("./" + requestPath.substring(requestPath.lastIndexOf('/') + 1) + "/");
            return;
        }

        response.setContentType("text/html");
        PrintWriter pw = response.getWriter();
        String path = Toolkit.ifEmpty(fileman.getPath(), "/");

        int length = fileman.getPath().split("/+").length;
        StringBuilder parents = new StringBuilder();
        for (int i = 0; i < length; i++) {
            parents.append("../");
        }

        pw.println("<html>");
        pw.println("<head>");
        pw.println("    <link rel=\"Shortcut Icon\" href=\"" + parents + "fileman.ico\">");
        pw.println("    <title>Index of " + path + "</title>");
        pw.println("    <meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\"/>");
        pw.println("</head>");
        pw.println("<body>");
        pw.println("<h1>Index of " + path + "</h1>");
        pw.println("<table cellspacing=\"10\" align=\"left\">");
        pw.println("    <thead>");
        Set<String> columns = fileman.getProperties().keySet();
        int index = 0;
        for (String column : columns) {
            if (index++ == 0) pw.println("        <th align=\"left\">" + column + "</th>");
            else pw.println("        <th>" + column + "</th>");
        }
        pw.println("    </thead>");
        pw.println("    <tr>");
        pw.println("        <td>");
        pw.println("            <a href=\"../\">Parent Directory</a>");
        pw.println("        </td>");
        List<Fileman> children = fileman.getChildren();
        for (Fileman child : children) {
            pw.println("    </tr>");
            Map<String, Object> properties = child.getProperties();
            for (String column : columns) {
                pw.println("        <td>" + properties.get(column) + "</td>");
            }
            pw.println("    </tr>");
        }
        pw.println("</table>");
        pw.println("</body>");
        pw.println("</html>");
        pw.flush();
    }
}
