package util;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.ErrorManager;
import java.util.logging.LogRecord;
import java.util.logging.StreamHandler;

/**
 * Created by Yongtao on 9/20/2015.
 *
 * The difference between default SocketHandler and this is that this handler tries to reconnect to server when failed.
 * Due to implementation limitation, config and security are not offered.
 *
 * Only very first connection failure will be reported (default jvm behaviour).
 * Do remember to change waitTime between retries to your need.
 */
public class ReconnectSocketHandler extends StreamHandler{
	protected AtomicBoolean reconnect=new AtomicBoolean(false);
	protected ExecutorService single=Executors.newSingleThreadExecutor();
	protected Runnable recoRun=new Reconnect();
	protected long waitTime=10*1000;    // 10 seconds

	class Reconnect implements Runnable{

		@Override
		public void run(){
			try{
				try{
					Thread.sleep(waitTime);
				}catch(InterruptedException ignored){
				}
				close();
				connect();
				reconnect.set(false);
			}catch(IOException e){
				single.submit(recoRun);
			}
		}
	}

	@Override
	protected void reportError(String msg,Exception ex,int code){
		super.reportError(msg,ex,code);
		if(ex instanceof IOException){
			if(!reconnect.getAndSet(true)){
				single.submit(recoRun);
			}
		}
	}

	// rest are copied from jdk

	private Socket sock;
	private String host;
	private int port;


	public ReconnectSocketHandler(String host,int port){
		this.port=port;
		this.host=host;
		try{
			connect();
		}catch(IOException e){
			reportError(null,e,ErrorManager.WRITE_FAILURE);
		}
	}

	public ReconnectSocketHandler(String host,int port,long retryTime){
		this.waitTime=retryTime;
		this.port=port;
		this.host=host;
		try{
			connect();
		}catch(IOException e){
			reportError(null,e,ErrorManager.WRITE_FAILURE);
		}
	}

	private void connect() throws IOException{
		// Check the arguments are valid.
		if(port==0){
			throw new IllegalArgumentException("Bad port: "+port);
		}
		if(host==null){
			throw new IllegalArgumentException("Null host name: "+host);
		}

		// Try to open a new socket.
		sock=new Socket(host,port);
		OutputStream out=sock.getOutputStream();
		BufferedOutputStream bout=new BufferedOutputStream(out);
		setOutputStream(bout);
	}

	/**
	 * Close this output stream.
	 *
	 * @throws SecurityException if a security manager exists and if
	 *                           the caller does not have <tt>LoggingPermission("control")</tt>.
	 */
	@Override
	public synchronized void close() throws SecurityException{
		super.close();
		if(sock!=null){
			try{
				sock.close();
			}catch(IOException ix){
				// drop through.
			}
		}
		sock=null;
	}

	/**
	 * Format and publish a <tt>LogRecord</tt>.
	 *
	 * @param record description of the log event. A null record is
	 *               silently ignored and is not published
	 */
	@Override
	public synchronized void publish(LogRecord record){
		if(!isLoggable(record)){
			return;
		}
		super.publish(record);
		flush();
	}
}
