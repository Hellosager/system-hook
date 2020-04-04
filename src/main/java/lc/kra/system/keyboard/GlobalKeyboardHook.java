/**
 * Copyright (c) 2016 Kristian Kraljic
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package lc.kra.system.keyboard;

import static lc.kra.system.GlobalHookMode.DEFAULT;
import static lc.kra.system.GlobalHookMode.RAW;
import static lc.kra.system.keyboard.event.GlobalKeyEvent.TS_DOWN;
import static lc.kra.system.keyboard.event.GlobalKeyEvent.VK_CONTROL;
import static lc.kra.system.keyboard.event.GlobalKeyEvent.VK_LCONTROL;
import static lc.kra.system.keyboard.event.GlobalKeyEvent.VK_LMENU;
import static lc.kra.system.keyboard.event.GlobalKeyEvent.VK_LSHIFT;
import static lc.kra.system.keyboard.event.GlobalKeyEvent.VK_LWIN;
import static lc.kra.system.keyboard.event.GlobalKeyEvent.VK_MENU;
import static lc.kra.system.keyboard.event.GlobalKeyEvent.VK_RCONTROL;
import static lc.kra.system.keyboard.event.GlobalKeyEvent.VK_RMENU;
import static lc.kra.system.keyboard.event.GlobalKeyEvent.VK_RSHIFT;
import static lc.kra.system.keyboard.event.GlobalKeyEvent.VK_RWIN;
import static lc.kra.system.keyboard.event.GlobalKeyEvent.VK_SHIFT;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

import lc.kra.system.GlobalHookMode;
import lc.kra.system.LibraryLoader;
import lc.kra.system.keyboard.event.GlobalKeyEvent;
import lc.kra.system.keyboard.event.GlobalKeyListener;

public class GlobalKeyboardHook {
	private static final int STATUS_SUCCESS = 0;
	
	private NativeKeyboardHook keyboardHook;
	
	private BlockingQueue<GlobalKeyEvent> inputBuffer =
		new LinkedBlockingQueue<GlobalKeyEvent>();
	private boolean menuPressed, shiftPressed, controlPressed, winPressed, extendedKey;
	
	private List<GlobalKeyListener> listeners = new CopyOnWriteArrayList<GlobalKeyListener>();
	
	private Set<Integer> holdDownKeyCodes = new HashSet<Integer>();
	
	
	private Thread eventDispatcher = new Thread() {{
			setName("Global Keyboard Hook Dispatcher");
			setDaemon(true);
		}
		
		public void run() {
			try {
				// while the global keyboard hook is alive, try to take events and dispatch them
				while(GlobalKeyboardHook.this.isAlive()) {
					GlobalKeyEvent event = inputBuffer.take();
					if(event.getTransitionState()==TS_DOWN)
					     keyPressed(event);
					else keyReleased(event);
				}
			} catch(InterruptedException e) { /* thread got interrupted, break */ }
		}
	};

	/**
	 * Instantiate a new GlobalKeyboardHook.
	 * 
	 * The constructor first tries to load the native library. On failure a {@link UnsatisfiedLinkError}
	 * is thrown. Afterwards the native keyboard hook is initialized. A {@link RuntimeException} is raised
	 * in case the hook could not be established.
	 * 
	 * Two separate threads are started by the class. The HookThread and a separate EventDispatcherThread.
	 * 
	 * @throws UnsatisfiedLinkError Thrown if loading the native library failed
	 * @throws RuntimeException Thrown if registering the low-level keyboard hook failed
	 */
	public GlobalKeyboardHook() throws UnsatisfiedLinkError { this(false); }
	
	/**
	 * Instantiate a new GlobalKeyboardHook.
	 * 
	 * @see #GlobalKeyboardHook()
	 * 
	 * @param raw Use raw input, instead of a low-level system hook. Raw input will provide additional information of the device
	 * @throws UnsatisfiedLinkError Thrown if loading the native library failed
	 * @throws RuntimeException Thrown if registering the low-level keyboard hook failed
	 */
	public GlobalKeyboardHook(boolean raw) throws UnsatisfiedLinkError { this(raw?RAW:DEFAULT);	}
	
	/**
	 * Instantiate a new GlobalKeyboardHook.
	 * 
	 * @see #GlobalKeyboardHook()
	 * 
	 * @param mode The mode to capture the input
	 * @throws UnsatisfiedLinkError Thrown if loading the native library failed
	 * @throws RuntimeException Thrown if registering the low-level keyboard hook failed
	 */
	public GlobalKeyboardHook(GlobalHookMode mode) throws UnsatisfiedLinkError {
		LibraryLoader.loadLibrary(); // load the library, in case it's not already loaded
		
		// register a keyboard hook (throws a RuntimeException in case something goes wrong)
		keyboardHook = new NativeKeyboardHook(mode) {
			/**
			 * Handle the input virtualKeyCode and transitionState, create event and add it to the inputBuffer
			 */
			@Override public void handleKey(int virtualKeyCode, int transitionState, char keyChar, long deviceHandle) {
				switchControlKeys(virtualKeyCode, transitionState);
				inputBuffer.add(new GlobalKeyEvent(this, virtualKeyCode, transitionState, keyChar, menuPressed, shiftPressed, controlPressed, winPressed, extendedKey, deviceHandle));			
			}
		};
		
		// start the event dispatcher after a successful hook
		eventDispatcher.start();
	}

	/**
	 * Adds a global key listener
	 * 
	 * @param listener The listener to add
	 */
	public void addKeyListener(GlobalKeyListener listener) { listeners.add(listener); }
	/**
	 * Removes a global key listener
	 * 
	 * @param listener The listener to remove
	 */
	public void removeKeyListener(GlobalKeyListener listener) { listeners.remove(listener); }

	/**
	 * Invoke keyPressed (transition state TS_DOWN) for all registered listeners
	 * 
	 * @param event A global key event
	 */
	private void keyPressed(GlobalKeyEvent event) {
		
		int virtualKeyCode = event.getVirtualKeyCode();
		
		// only add if key is not already hold down
		if(!isKeyHoldDown(event))
			holdDownKeyCodes.add(virtualKeyCode);
		
		for(GlobalKeyListener listener:listeners)
			listener.keyPressed(event);
	}
	
	/**
	 * Invoke keyReleased (transition state TS_UP) for all registered listeners
	 * 
	 * @param event A global key event
	 */
	private void keyReleased(GlobalKeyEvent event) {

		int virtualKeyCode = event.getVirtualKeyCode();

		// redundant if - key should always be hold down if you can release it, but you never know
		if(isKeyHoldDown(event))
			holdDownKeyCodes.remove(virtualKeyCode);
		
		for(GlobalKeyListener listener:listeners)
			listener.keyReleased(event);
	}

	/**
	 * Checks if the specified key is currently hold down
	 * 
	 * @param virtualKeyCode the virtual code of the key, use constants in {@link GlobalKeyEvent}
	 * 
	 * @return true if the key is currently hold down
	 */
	public boolean isKeyHoldDown(int virtualKeyCode) {
		return holdDownKeyCodes.contains(virtualKeyCode);
	}
	
	
	/**
	 * Checks if all the specified keys are currently hold down
	 * 
	 * @param virtualKeyCodes any number of specified key codes, use constants in {@link GlobalKeyEvent}
	 * 
	 * @return true if all the specified keys are currently hold down, false if any of the keys is not currently hold down
	 */
	public boolean areKeysHoldDown(int... virtualKeyCodes) {
		for(int keyCode : virtualKeyCodes) {
			if(!isKeyHoldDown(keyCode)) {
				return false;
			}
		}
		return true;
	}
	
	private boolean isKeyHoldDown(GlobalKeyEvent event) {
		return isKeyHoldDown(event.getVirtualKeyCode());
	}
	
	/**
	 * Checks whether the keyboard hook is still alive and capturing inputs
	 * 
	 * @return true if the keyboard hook is alive
	 */
	public boolean isAlive() { return keyboardHook!=null&&keyboardHook.isAlive(); }
	/**
	 * Shutdown the keyboard hook in case it is still alive.
	 * 
	 * This method does nothing if the hook already shut down and will block until shut down.
	 */
	public void shutdownHook() {
		if(isAlive()) {
			keyboardHook.unregisterHook();
			try { keyboardHook.join(); }
			catch(InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
	}
	
	/**
	 * Lists all connected keyboards
	 * 
	 * @return A map of device handles and display names
	 */
	public static Map<Long,String> listKeyboards() throws UnsatisfiedLinkError {
		LibraryLoader.loadLibrary(); // load the library, in case it's not already loaded
		return NativeKeyboardHook.listDevices();
	}
	
	private static abstract class NativeKeyboardHook extends Thread {
		private int status;
		private GlobalHookMode mode;
		
		public NativeKeyboardHook(GlobalHookMode mode)  {
			super("Global Keyboard Hook Thread");
			setDaemon(false); setPriority(MAX_PRIORITY);
			synchronized(this) {
				this.mode = mode;
				try { start(); wait(); }
				catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
				
				if(status!=STATUS_SUCCESS)
					throw new RuntimeException("Low-level keyboard hook failed ("+status+")");
			}
		}
		
		@Override public void run() {
			status = registerHook(mode.ordinal());
			synchronized(this) {
				notifyAll(); }
		}
		
		public native final int registerHook(int mode);
		public native final void unregisterHook();
		
		public static native final Map<Long,String> listDevices();
		
		public abstract void handleKey(int virtualKeyCode, int transitionState, char keyChar, long deviceHandle);
	}
	
	/**
	 * Switch control states for menu/shift/control
	 */
	private void switchControlKeys(int virtualKeyCode, int transitionState) {
		boolean downTransition = (transitionState==TS_DOWN);
		switch(virtualKeyCode) {
		case VK_RWIN: extendedKey = downTransition; 
		case VK_LWIN: 
			winPressed = downTransition;
			break;
		case VK_RMENU: extendedKey = downTransition;
		case VK_MENU: case VK_LMENU:
			menuPressed = downTransition;
			break;
		case VK_RSHIFT: extendedKey = downTransition;
		case VK_SHIFT: case VK_LSHIFT:
			shiftPressed = downTransition;
			break;
		case VK_RCONTROL: extendedKey = downTransition;
		case VK_CONTROL: case VK_LCONTROL:
			controlPressed = downTransition;
			break;
		}
	}
}