package sample.log;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by Yongtao on 9/13/2015.
 */
public class InputStreamProxy extends FilterInputStream{
	protected AtomicLong counter;

	public InputStreamProxy(InputStream in,AtomicLong counter){
		super(in);
		this.counter=counter;
	}

	@Override
	public int read() throws IOException{
		int result=in.read();
		if(result>0)
			counter.incrementAndGet();
		return result;
	}

	@Override
	public int read(byte[] b) throws IOException{
		int result=in.read(b);
		if(result>0) counter.addAndGet(result);
		return result;
	}

	@Override
	public int read(byte[] b,int off,int len) throws IOException{
		int result=in.read(b,off,len);
		if(result>0) counter.addAndGet(result);
		return result;
	}
}
