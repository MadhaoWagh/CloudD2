# Intro
This is code base for CS 6343 - CLOUD COMPUTING

# How to use
The heart of this piece of code is IOControl. It wraps on TCP socket and offers request/response interface as well as server in filter/handler structure.

A request is represent by a Session, which you can insert any key/value into request and extract from server side. A session has its MsgType, which you can define yourself by implementing MsgType interface.

A MsgFilter intercepts raw connect without extracting session (you should not extract session manually, The framework will extract for you). Multiple filters can be chained.

After that, session info is extract by the framework, and pass it to registered MsgHandler for specific MsgType. Also, multiple handlers can be chained. It's important to return false after responding to stop any further handler from giving response more than once.

Filters and handlers are registered by calling IOControl's registerXXX methods before any IO operations.

The supported IO operations are: send, request, response.

If IOControl.startServer is called, it is in server mode. Call waitServer to block your main thread if it has no other thing to do so it won't exit until server receives quit command. If IOControl.startServer is not called, it will be in client mode, and filters/handlers can still be used.

# Samples
* SimpleLogServer - A remote log server. You can redirect your log to it. It should be started before other server of coz.
* SimpleServer - A simple server that responses to echo msg and also file read.
* SimpleEchoClient - Command line client that sends your input to SimpleServer.
* SampleFileReadClient - Command line client that reads remote file given file path.
* SystemInfo - Demo on how to get system load info.

# Javadoc
Some javadoc is offered. So if you are unsure about how to use the code, first check samples, then read javadoc, then if it's still unclear, send me email.

# Note to D
I'm still testing file write sample, please be patient and try existing code. It's hard to totally decouple read/write operation from other functions, so I put them into sample folder and you can modify the code to your need.
