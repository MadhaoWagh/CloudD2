package sample.log;

import org.ini4j.Wini;
import util.Log;
import util.ReconnectSocketHandler;
import util.SingleLineFormatter;

import java.io.File;
import java.io.IOException;
import java.util.logging.Handler;

public class Utils{
	public static void connectToLogServer(Log log) throws IOException{
		Wini conf=new Wini(new File("conf/sample/sample.ini"));
		String logIP=conf.get("log","ip");
		int logPort=conf.get("log","port",int.class);
		// set remote log server to forward all logs there
		Handler handler=new ReconnectSocketHandler(logIP,logPort);
		handler.setFormatter(new SingleLineFormatter());
		log.getParent().addHandler(handler);
	}
}
