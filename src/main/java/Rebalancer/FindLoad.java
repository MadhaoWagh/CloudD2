package Rebalancer;
import com.sun.management.OperatingSystemMXBean;
import sample.log.Utils;
import util.Log;

import java.io.IOException;
import java.lang.management.ManagementFactory;
public class FindLoad {
	/**
	 * Created by MadhaoWagh on 10/14/2015.
	 * Finds load of the server node
	 * <p/>
	 */
		private static final Log log=Log.get();
		private static final long mb=1024*1024;

		public static Double FindLoad() throws InterruptedException, IOException{
			Utils.connectToLogServer(log);

			OperatingSystemMXBean os=ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
			Runtime runtime=Runtime.getRuntime();
			Double Load=0.0;

			//for(;;){
				// Please note that cpu usage may be negative,
				// because jvm takes time to calculate used cpu time over total cpu time to get ratio.
				log.i("System load: "+os.getSystemCpuLoad()+"\tProcess load: "+os.getProcessCpuLoad()+
						"\tFree memory: "+runtime.freeMemory()/mb+"MB/"+runtime.totalMemory()/mb+"MB"
						+" Ratio: "+((double)runtime.freeMemory())/runtime.totalMemory());
				Thread.sleep(2*1000);
				Load=os.getSystemCpuLoad()+os.getProcessCpuLoad()+((double)runtime.freeMemory())/runtime.totalMemory();
				return Load;
			//}
		

	}
		public static void  main(String args[])
		{
			try {
				Double outLoad=FindLoad();
				System.out.println("Load: "+outLoad);
			} catch (InterruptedException | IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		


}
