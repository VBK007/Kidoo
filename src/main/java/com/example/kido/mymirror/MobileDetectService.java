package com.example.kido.mymirror;

import java.util.List;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.kido.common.ApiException;
import com.example.kido.mymirror.model.ClientHeaders;
import com.example.kido.mymirror.model.PageContent;
import com.example.kido.mymirror.repo.MobileDetectRepository;

@Service
public class MobileDetectService {

    private static final Pattern APP_PARAM = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final MobileDetectRepository repository;

    public MobileDetectService(MobileDetectRepository repository) {
        this.repository = repository;
    }

    public PageContent extractPageContent(String app, ClientHeaders client) {
        if (app == null || !APP_PARAM.matcher(app).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "app must be 1-32 letters, digits, - or _");
        }
        String html = repository.fetchHomePage(app, client);

        Document doc = Jsoup.parse(html == null ? "" : html);

        List<String> ids = doc.select("[id]").stream()
                .map(Element::id)
                .toList();

        // absUrl resolves relative src against the page; falls back to the raw attr.
        List<String> images = doc.select("img[src]").stream()
                .map(img -> img.absUrl("src").isEmpty() ? img.attr("src") : img.absUrl("src"))
                .toList();

        List<String> titles = doc.select("title").stream()
                .map(Element::text)
                .toList();

        return new PageContent(ids, images, titles);
    }
}
