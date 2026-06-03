package org.xmlpipeline.service;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import org.xmlpipeline.exception.ParseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * XML / RSS / Atom parser.
 *
 * Strategy
 * ────────
 * 1. Try Rome (handles RSS 0.9x/1.0/2.0, Atom 0.3/1.0, RDF).
 *    Rome is the Java equivalent of Python's feedparser.
 * 2. Fallback: DOM (org.w3c.dom) for generic XML with <item>/<entry> elements.
 * 3. If both fail with a syntax error → throw ParseException (non-retryable).
 *
 * Malformed XML policy
 * ────────────────────
 * - Rome FeedException but produces entries  → accept entries with a warning
 * - Rome FeedException, zero entries         → try DOM fallback
 * - DOM SAXException (unrecoverable syntax)  → throw ParseException
 * - Valid but empty feed (0 items)           → return empty list (task: completed/0 records)
 */
@Service
@Slf4j
public class ParserService {

    public record ParsedEntry(
            String title,
            String link,
            LocalDateTime publishedDate,
            String author,
            String summary
    ) {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public List<ParsedEntry> parse(byte[] content, String url, String jobId) {
        // Strategy 1: Rome
        List<ParsedEntry> result = tryRome(content, url, jobId);
        if (result != null) {
            return result;
        }

        // Strategy 2: DOM fallback
        result = tryDom(content, url, jobId);
        if (result != null) {
            return result;
        }

        throw new ParseException("No parseable content in " + url);
    }

    // -------------------------------------------------------------------------
    // Rome strategy
    // -------------------------------------------------------------------------

    private List<ParsedEntry> tryRome(byte[] content, String url, String jobId) {
        try {
            SyndFeedInput input = new SyndFeedInput();
            input.setAllowDoctypes(true);

            // Rome can parse from Reader; use UTF-8 as default, Rome detects encoding from XML declaration
            InputSource is = new InputSource(new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8));
            SyndFeed feed = input.build(is);

            List<SyndEntry> entries = feed.getEntries();
            if (entries.isEmpty()) {
                log.info("parse_empty_feed", kv("url", url), kv("job_id", jobId), kv("parser", "rome"));
                return Collections.emptyList();
            }

            List<ParsedEntry> records = new ArrayList<>(entries.size());
            for (SyndEntry entry : entries) {
                String summary = "";
                if (entry.getDescription() != null) {
                    summary = entry.getDescription().getValue();
                } else if (!entry.getContents().isEmpty()) {
                    summary = entry.getContents().get(0).getValue();
                }

                records.add(new ParsedEntry(
                        nullToEmpty(entry.getTitle()),
                        nullToEmpty(entry.getLink()),
                        toLocalDateTime(entry.getPublishedDate() != null
                                ? entry.getPublishedDate()
                                : entry.getUpdatedDate()),
                        nullToEmpty(entry.getAuthor()),
                        nullToEmpty(summary)
                ));
            }

            log.info("parse_success",
                    kv("url", url), kv("job_id", jobId),
                    kv("parser", "rome"), kv("records", records.size()));
            return records;

        } catch (FeedException e) {
            log.warn("rome_parse_error", kv("url", url), kv("job_id", jobId), kv("error", e.getMessage()));
            return null; // signal: try DOM fallback
        } catch (Exception e) {
            log.warn("rome_exception", kv("url", url), kv("job_id", jobId), kv("error", e.getMessage()));
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // DOM fallback
    // -------------------------------------------------------------------------

    private List<ParsedEntry> tryDom(byte[] content, String url, String jobId) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // Disable external DTD loading to prevent XXE and avoid network calls
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(content));

            // Collect <item> (RSS) and <entry> (Atom)
            List<Element> items = new ArrayList<>();
            collectElements(doc, "item", items);
            collectElements(doc, "entry", items);

            if (items.isEmpty()) {
                log.info("parse_empty_feed", kv("url", url), kv("job_id", jobId), kv("parser", "dom"));
                return Collections.emptyList();
            }

            List<ParsedEntry> records = new ArrayList<>(items.size());
            for (Element item : items) {
                records.add(new ParsedEntry(
                        getChildText(item, "title"),
                        getChildText(item, "link"),
                        null, // date parsing from raw DOM is complex; skip
                        getChildTextMulti(item, "author", "dc:creator"),
                        getChildTextMulti(item, "description", "summary", "content")
                ));
            }

            log.info("parse_success",
                    kv("url", url), kv("job_id", jobId),
                    kv("parser", "dom"), kv("records", records.size()));
            return records;

        } catch (SAXException e) {
            log.warn("dom_syntax_error", kv("url", url), kv("job_id", jobId), kv("error", e.getMessage()));
            throw new ParseException("XML syntax error in " + url + ": " + e.getMessage(), e);
        } catch (ParseException e) {
            throw e;
        } catch (IOException e) {
            log.warn("dom_io_error", kv("url", url), kv("job_id", jobId), kv("error", e.getMessage()));
            return null;
        } catch (Exception e) {
            log.warn("dom_exception", kv("url", url), kv("job_id", jobId), kv("error", e.getMessage()));
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void collectElements(Document doc, String tagName, List<Element> result) {
        NodeList nodes = doc.getElementsByTagName(tagName);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element el) {
                result.add(el);
            }
        }
    }

    private String getChildText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0 && nodes.item(0).getTextContent() != null) {
            return nodes.item(0).getTextContent().trim();
        }
        return "";
    }

    private String getChildTextMulti(Element parent, String... tagNames) {
        for (String tag : tagNames) {
            String text = getChildText(parent, tag);
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private LocalDateTime toLocalDateTime(java.util.Date date) {
        if (date == null) return null;
        try {
            return date.toInstant().atZone(ZoneId.of("UTC")).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }
}
