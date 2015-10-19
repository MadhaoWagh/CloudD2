package sample;

import net.LogServer;
import org.ini4j.Wini;

import java.io.File;
import java.io.IOException;

/**
 * Created by Yongtao on 9/17/2015.
 *
 * Demo log server. It should be started before other clients/servers.
 */
public class LogPrintServer{
	public static void main(String args[]){
		try{
			Wini conf=new Wini(new File("conf/sample/sample.ini"));
			int port=conf.get("log","port",int.class);
			new net.LogServer(port);
		}catch(IOException e){
			e.printStackTrace();
		}
	}
}
