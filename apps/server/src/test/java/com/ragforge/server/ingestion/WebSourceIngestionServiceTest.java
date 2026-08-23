package com.ragforge.server.ingestion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebSourceIngestionServiceTest {
    @Test
    void convertsHtmlToBoundedTextWithoutScriptsOrMarkup() {
        String markdown = WebSourceIngestionService.htmlToMarkdown(
                "<html><head><title>知识页</title><script>alert('x')</script></head>"
                        + "<body><h1>标题</h1><p>正文 &amp; 说明</p></body></html>");

        assertThat(markdown).doesNotContain("alert", "<h1>", "</p>");
        assertThat(markdown).contains("标题", "正文 & 说明");
        assertThat(WebSourceIngestionService.extractTitle("<title>知识页</title>")).isEqualTo("知识页");
    }
}
