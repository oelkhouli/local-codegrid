package dev.codegrid.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class Json {
  private final ObjectMapper mapper;

  public Json(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot encode value", e);
    }
  }

  public Object read(String value) {
    try {
      return mapper.readValue(value, Object.class);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored JSON", e);
    }
  }
}
