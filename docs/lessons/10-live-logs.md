# 10. Durable live logs and React state

A WebSocket transports events; it is not the durable record. Each accepted event is stored with a monotonically increasing sequence for its job. The sequence increment and event insert share a transaction.

The worker numbers log chunks within an attempt. The API accepts the next sequence, acknowledges an identical duplicate, and rejects a conflicting duplicate or sequence gap. New writes require current attempt authority. Output byte limits apply independently of network message counts.

On connection, the browser supplies the last event sequence it has processed. The server reads later persisted events in order. After disconnect, the browser reconnects with bounded backoff and the same cursor. Already processed sequences are ignored. This produces replayable delivery; it does not claim that the network itself delivers each message once.

The server periodically rechecks session validity. It limits connection counts and bounds send buffers and worker queues. A slow consumer must not cause unbounded memory growth. Logs are rendered as React text, so a program printing an HTML script tag does not execute that script in the browser.

In `App.tsx`, state drives rendering. Effects connect the socket, fetch a selected job and clean up when selection changes. A ref holds the current selected ID so late HTTP responses do not replace a different selected job. The editor draft and the submitted immutable source are separate values.

**Exercise:** open a completed run again and explain why its output appears even though the program has stopped. Then disconnect and reconnect during an active run and inspect sequence numbers.

**Explain it:** “Live output is a replayable view of committed events. I can rebuild the display from PostgreSQL after a socket failure.”

Do not use metric labels for job IDs; durable job events and bounded structured logs are the appropriate place for that detail.
