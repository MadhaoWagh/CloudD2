package sample;

import net.MsgHandler;
import net.MsgType;
import net.Session;
import util.Log;

import java.net.Socket;

/**
 * Created by Yongtao on 9/10/2015.
 * <p/>
 * This is sample msg handler simply recording incoming session info.
 */
public class SimpleLogger implements MsgHandler{
	private static Log log=Log.get();

	@Override
	public boolean process(Session session){
		Socket socket=session.getSocket();
		String ip=socket.getInetAddress().getHostAddress();
		int port=socket.getPort();
		MsgType type=session.getType();
		log.i("Receive "+type+" from "+ip+":"+port+" "+session.getKeyValuePairs());
		return true;
	}
}
