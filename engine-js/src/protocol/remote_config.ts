import { callHost } from "./command";

type FeatureValue = string | boolean | number;

// A value that is absent, null, or unavailable reads as the default.
// Hosts before this command refuse it, so a failed read must not fail the job asking.
async function get<T extends FeatureValue>(key: string, defaultValue: T): Promise<T> {
  try {
    return await callHost("remote-config/get", async function (pipe) {
      let pending = pipe.sink.writeObjectAsJson({ "key": key });
      if (pending) await pending;
      pending = pipe.sink.close();
      if (pending) await pending;
      const value = ((await pipe.source.readJsonObject()) as Record<string, unknown>)["value"];
      return value == null ? defaultValue : (value as T);
    });
  } catch (e) {
    console.warn("remote-config/get " + key + ": " + String(e));
    return defaultValue;
  }
}

export const RemoteConfig = { get: get };
