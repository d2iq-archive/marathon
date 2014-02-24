## POST `/v2/event_subscriptions?callback_url={callback_url}`

Register a callback url as event subscriber.

_NOTE: To activate this endpoint, you need to startup a Marathon instance with `--event_subscriber http_callback`_

### Example

**Request:**


```
POST /v2/event_subscriptions?callback_url=http://localhost:9292/callback HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
Host: localhost:8080
User-Agent: HTTPie/0.8.0
```

**Response:**


```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "callback_uri": "http://localhost:9292/callback",
    "clientIp": "127.0.0.1",
    "eventType": "subscribe_event"
}
```