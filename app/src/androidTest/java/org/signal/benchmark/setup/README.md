These test helpers and `org.whispersystems.signalservice.internal.websocket.BenchmarkWebSocketConnection`
were copied from Signal Android commit `6e9dddc01f1b26e608ac683807c51e139c141ed2`,
under `app/src/benchmarkShared/java`. They support the incoming-message observer
instrumentation tests without including the benchmark application or its entry points.
`TestUsers` is adapted to Molly's instrumentation master-secret initialization.
