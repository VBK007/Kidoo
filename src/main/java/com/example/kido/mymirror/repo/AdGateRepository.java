package com.example.kido.mymirror.repo;

@Repository
public class AdGateRepository {
    private final RestTemplate restTemplate;
    public AdGateRepository(RestTemplate restTemplate) { this.restTemplate = restTemplate; }

    public String verifyAds(String baseUrl, String cookie) {
        String url = baseUrl + "/mobile/verify2.php";
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Mozilla/5.0 (Linux; Android 10; Core-T4)");
        headers.set("X-Requested-With", "XMLHttpRequest");
        headers.set("Cookie", cookie);

        HttpEntity<String> request = new HttpEntity<>(headers);
        return restTemplate.exchange(url, HttpMethod.GET, request, String.class).getBody();
    }
}
