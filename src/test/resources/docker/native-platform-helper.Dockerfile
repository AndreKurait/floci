FROM busybox:1.37.0
COPY helper-context.txt /helper-context.txt
CMD ["sh", "-c", "test \"$(cat /helper-context.txt)\" = declared-helper-context && uname -m"]
