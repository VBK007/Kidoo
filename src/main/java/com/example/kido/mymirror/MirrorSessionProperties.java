package com.example.kido.mymirror;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.mymirror.session")
public class MirrorSessionProperties {
    private String toolDir = "tools/netmirror-capture";
    private String sessionFile = "";
    private boolean keepAliveEnabled = true;
}
