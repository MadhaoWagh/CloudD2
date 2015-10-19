package sample;

import net.IOControl;
import net.Session;
import org.ini4j.Wini;
import sample.log.Utils;
import util.Log;

import java.io.File;
import java.io.IOException;
import java.util.Scanner;

/**
 * Created by Yongtao on 9/10/2015.
 * <p/>
 * This is demo how to use IOControl as client.
 */
public class EchoClient{
	private static Log log=Log.get();

	public static void main(String args[]){
		try{
			Utils.connectToLogServer(log);
			//  read conf file here
			Wini conf=new Wini(new File("conf/sample/sample.ini"));
			String serverIP=conf.get("read server","ip");
			int serverPort=conf.get("read server","port",int.class);

			try{
				IOControl control=new IOControl();
				//  get what you type
				Scanner in=new Scanner(System.in);
				for(;;){
					String cmd=in.nextLine();
					if(cmd.length()>0){
						String test=cmd.toLowerCase().trim();
						//  ask server to quit
						if(test.equals("quit") || test.equals("exit") || test.equals("q") || test.equals("e")){
							control.send(new Session(EchoMsgType.EXIT_SERVER),serverIP,serverPort);
							break;
						}
					}
					// else just send plain ping msg.
					Session session=new Session(EchoMsgType.ECHO);
					session.set("Comment",cmd);
					Session ping=control.request(session,serverIP,serverPort);
					log.i("Heard: "+ping.getType());
				}
			}catch(Exception e){
				log.w(e);
			}
		}catch(IOException e){
			log.w(e);
		}
	}
}
