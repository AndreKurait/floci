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
destination.write_text(json.dumps(summary,indent=2)+"\n")
