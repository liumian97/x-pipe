package com.ctrip.xpipe.observer;

import java.util.LinkedList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.ctrip.xpipe.api.lifecycle.Lifecycle;
import com.ctrip.xpipe.api.observer.Observable;
import com.ctrip.xpipe.api.observer.Observer;
import com.ctrip.xpipe.lifecycle.AbstractLifecycle;

/**
 * @author wenchao.meng
 *
 * May 18, 2016 4:14:57 PM
 */
public abstract class AbstractLifecycleObservable extends AbstractLifecycle implements Observable, Lifecycle{
	
	protected Logger logger = LogManager.getLogger(getClass());
	
	private List<Observer> observers = new LinkedList<>();
	
	@Override
	public synchronized void addOObserver(Observer observer) {
		
		observers.add(observer);
		
	}

	public void remoteObserver(Observer observer) {
		
		observers.remove(observer);
	}
	
	
	protected void notifyObservers(Object arg){
		
		for(Observer observer : observers){
			
			try{
				observer.update(arg, this);
			}catch(Exception e){
				logger.error("[notifyObservers]" + observer, e);
			}
		}
	}
}
