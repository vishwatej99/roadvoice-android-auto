import { createBroker, readConfig } from './broker.mjs';

let config;
try {
  config = readConfig();
} catch (error) {
  console.error(error.message);
  process.exit(1);
}

const server = createBroker(config);
server.on('error', () => {
  console.error('RoadVoice could not bind the configured host and port.');
  process.exitCode = 1;
});
server.listen(config.port, config.host, () => {
  console.log('RoadVoice GPT-Live broker is listening. Connect the Android app through USB adb reverse.');
});
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    server.close();
    const shutdownTimer = setTimeout(() => server.closeAllConnections(), 5_000);
    shutdownTimer.unref();
  });
}
