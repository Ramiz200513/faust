package com.jarvis.relay.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ImageFormat {
    @JsonProperty("jpeg") JPEG,
    @JsonProperty("png") PNG
}
