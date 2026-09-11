package dev.flowstream.core;

import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.URI;
import java.util.*;

/** Bounded UTF-8 DAV multistatus parser. DTD/entity declarations are never permitted. */
public final class DavParser {
    public static final int MAX_XML_BYTES = 8 * 1024 * 1024;
    public static final int MAX_ITEMS = 10000;
    private DavParser() { }
    public static List<RemoteItem> parse(String xml, DavUrls urls, String directory) throws IOException {
        if (xml == null || xml.length() > MAX_XML_BYTES) throw new IOException("目录响应过大");
        if (xml.indexOf('\u0000') >= 0) throw new IOException("目录响应必须使用 UTF-8");
        String upper = xml.toUpperCase(Locale.ROOT);
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")) throw new IOException("拒绝 XML DTD / 实体声明");
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true); f.setExpandEntityReferences(false);
            optionalFeature(f, "http://xml.org/sax/features/external-general-entities", false);
            optionalFeature(f, "http://xml.org/sax/features/external-parameter-entities", false);
            optionalFeature(f, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setEntityResolver((publicId, systemId) -> { throw new SAXException("External entity denied"); });
            b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
            });
            Document document = b.parse(new InputSource(new StringReader(xml)));
            if (!"multistatus".equals(document.getDocumentElement().getLocalName())
                    || !"DAV:".equals(document.getDocumentElement().getNamespaceURI()))
                throw new IOException("服务器没有返回 DAV multistatus");
            NodeList responses = document.getElementsByTagNameNS("DAV:", "response");
            if (responses.getLength() > MAX_ITEMS + 1) throw new IOException("单个目录超过 10000 项，请拆分目录");
            LinkedHashMap<String, RemoteItem> found = new LinkedHashMap<>();
            URI current = urls.uri(directory, true);
            for (int i = 0; i < responses.getLength(); i++) {
                Element response = (Element) responses.item(i);
                String href = childText(response, "href");
                if (href.isEmpty()) continue;
                String path;
                try { path = urls.relative(current, href); } catch (IllegalArgumentException ignored) { continue; }
                if (path.equals(MediaPaths.normalize(directory)) || !MediaPaths.parent(path).equals(MediaPaths.normalize(directory))) continue;
                NodeList propstats = response.getElementsByTagNameNS("DAV:", "propstat");
                boolean success = false, dir = false; String display = ""; long size = 0;
                for (int j = 0; j < propstats.getLength(); j++) {
                    Element ps = (Element) propstats.item(j);
                    if (!childText(ps, "status").matches("(?s).*\\s2\\d\\d(?:\\s.*)?")) continue;
                    success = true;
                    NodeList props = ps.getElementsByTagNameNS("DAV:", "prop");
                    if (props.getLength() == 0) continue;
                    Element p = (Element) props.item(0);
                    dir |= p.getElementsByTagNameNS("DAV:", "collection").getLength() != 0;
                    String name = childText(p, "displayname"); if (!name.isEmpty()) display = name;
                    try { size = Long.parseLong(childText(p, "getcontentlength")); } catch (NumberFormatException ignored) { }
                }
                if (success) found.put(path, new RemoteItem(path, display, dir, size));
            }
            return new ArrayList<>(found.values());
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("WebDAV 目录解析失败", e); }
    }
    private static void optionalFeature(DocumentBuilderFactory f, String name, boolean value) {
        try { f.setFeature(name, value); } catch (javax.xml.parsers.ParserConfigurationException ignored) {
            // Android XML providers differ; declaration rejection + resolver remain mandatory.
        }
    }
    private static String childText(Element e, String localName) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && "DAV:".equals(n.getNamespaceURI()) && localName.equals(n.getLocalName())) return n.getTextContent().trim();
        }
        return "";
    }
}
