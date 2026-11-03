import collections
import datetime
import json
import pathlib
import re
import subprocess
import sys

PREFIXES = ("java.", "javax.", "jdk.", "sun.", "com.sun.", "io.quarkus.",
            "io.vertx.", "org.jboss.", "org.apache.", "io.github.hectorvent.floci.",
            "org.codehaus.", "org.junit.", "com.fasterxml.")


def timestamp(value):
    normalized = re.sub(r"(\.\d{6})\d+", r"\1", value.replace("Z", "+00:00"))
    return datetime.datetime.fromisoformat(normalized)


def frame_name(frame):
    method = frame.get("method") or {}
    owner = str((method.get("type") or {}).get("name", "")).replace("/", ".")
    name = owner + "." + str(method.get("name", ""))
    if name.startswith(PREFIXES) and re.fullmatch(r"[A-Za-z0-9_.$<>/]+", name):
        return name
    return None


def summarize(events):
    samples = [event for event in events if event["type"] == "jdk.ExecutionSample"]
    timed = [(timestamp(event["values"]["startTime"]), event) for event in samples
             if isinstance(event.get("values", {}).get("startTime"), str)]
    if not timed:
        return {"scope": "No timestamped execution samples available", "sampleCount": len(samples)}
    origin = min(time for time, event in timed)
    windows = {}
    for time, event in timed:
        index = int((time - origin).total_seconds() // 30)
        window = windows.setdefault(index, {"samples": 0, "tops": collections.Counter(),
                                           "flociCallers": collections.Counter()})
        window["samples"] += 1
        frames = (event.get("values", {}).get("stackTrace") or {}).get("frames", [])
        names = [frame_name(frame) for frame in frames]
        top = names[0] if names else None
        if top:
            window["tops"][top] += 1
            caller = next((name for name in names if name and name.startswith("io.github.hectorvent.floci.")), None)
            if caller:
                window["flociCallers"][top + " <- " + caller] += 1
    return {"scope": "30-second windows relative to first execution sample, not JVM start. Counts are samples, not elapsed CPU duration. Only allowlisted method names are retained; raw values and absolute timestamps are excluded.",
            "sampleCount": len(samples), "timestampedSampleCount": len(timed),
            "windows": [{"startOffsetSeconds": index * 30, "sampleCount": window["samples"],
                         "topFrames": window["tops"].most_common(15),
                         "topFrameWithFlociCaller": window["flociCallers"].most_common(15)}
                        for index, window in sorted(windows.items())]}



import collections, json, pathlib, subprocess, sys
recording=pathlib.Path(sys.argv[1]); destination=pathlib.Path(sys.argv[2])
command=["jfr","print","--json","--events","jdk.ExecutionSample,jdk.CPULoad,jdk.GCPhasePause",str(recording)]
raw=json.loads(subprocess.check_output(command)); counts=collections.Counter(); tops=collections.Counter(); framework=collections.Counter(); cpu=[]; pauses=[]
for event in raw["recording"]["events"]:
 kind=event["type"];values=event.get("values",{});counts[kind]+=1
 if kind=="jdk.CPULoad":cpu.append({k:values[k] for k in ["jvmUser","jvmSystem","machineTotal"] if isinstance(values.get(k),(int,float))})
 if kind=="jdk.GCPhasePause":pauses.append(values.get("duration"))
 if kind=="jdk.ExecutionSample":
  frames=(values.get("stackTrace") or {}).get("frames",[])
  for index,frame in enumerate(frames):
   method=frame.get("method") or {};decl=method.get("type") or {};name=str(decl.get("name","")).replace("/",".")+"."+str(method.get("name",""))
   if not name.startswith(("java.","javax.","jdk.","sun.","com.sun.","io.quarkus.","io.vertx.","org.jboss.","org.apache.","io.github.hectorvent.floci.","org.codehaus.","org.junit.","com.fasterxml.")):continue
   if index==0:tops[name]+=1
   if name.startswith(("io.quarkus.","org.jboss.jandex.","io.github.hectorvent.floci.")):framework[name]+=1
summary=dict(scope="Sample/event counts only; nested frame counts are not elapsed durations or independent initializations. Raw recordings and values are not public artifacts.",eventCounts=dict(counts),topSampleFrames=tops.most_common(30),frameworkFrameOccurrences=framework.most_common(40),cpu={key:dict(min=min(x[key]for x in cpu if key in x),max=max(x[key]for x in cpu if key in x),mean=sum(x[key]for x in cpu if key in x)/sum(key in x for x in cpu))for key in ["jvmUser","jvmSystem","machineTotal"] if any(key in x for x in cpu)},gcPauseDurations=pauses)
summary["executionSampleWindows"] = summarize(raw["recording"]["events"])
destination.write_text(json.dumps(summary,indent=2)+"\n")
