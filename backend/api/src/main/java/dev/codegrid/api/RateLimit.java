package dev.codegrid.api;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RateLimit {
  private final StringRedisTemplate redis;
  // Redis TIME prevents disagreement between API replica clocks. Hash and expiry change atomically.
  private static final DefaultRedisScript<Long> BUCKET =
      new DefaultRedisScript<>(
          """
local now = redis.call('TIME')
local t = tonumber(now[1]) + tonumber(now[2]) / 1000000
local old = redis.call('HMGET', KEYS[1], 'tokens', 'at')
local cap = tonumber(ARGV[1]); local rate = tonumber(ARGV[2])
local tokens = math.min(cap, tonumber(old[1] or cap) + math.max(0, t-tonumber(old[2] or t))*rate)
local allowed = 0
if tokens >= 1 then tokens = tokens - 1; allowed = 1 end
redis.call('HSET', KEYS[1], 'tokens', tokens, 'at', t)
redis.call('EXPIRE', KEYS[1], math.ceil(cap/rate)+1)
return allowed
""",
          Long.class);

  public RateLimit(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public void take(String key, int capacity, double perSecond) {
    try {
      Long result = redis.execute(BUCKET, List.of("rate:" + key), "" + capacity, "" + perSecond);
      if (!Long.valueOf(1).equals(result))
        throw new ApiException(429, "Rate limit exceeded; try again shortly");
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(503, "Admission is unavailable; retry later");
    }
  }
}
