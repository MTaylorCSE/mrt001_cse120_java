package nachos.threads;

import nachos.machine.*;

/**
 * A KThread is a thread that can be used to execute Nachos kernel code. Nachos
 * allows multiple threads to run concurrently.
 *
 * To create a new thread of execution, first declare a class that implements
 * the <tt>Runnable</tt> interface. That class then implements the <tt>run</tt>
 * method. An instance of the class can then be allocated, passed as an argument
 * when creating <tt>KThread</tt>, and forked. For example, a thread that
 * computes pi could be written as follows:
 *
 * <p>
 * <blockquote>
 *
 * <pre>
 * class PiRun implements Runnable {
 * 	public void run() {
 *         // compute pi
 *         ...
 *     }
 * }
 * </pre>
 *
 * </blockquote>
 * <p>
 * The following code would then create a thread and start it running:
 *
 * <p>
 * <blockquote>
 *
 * <pre>
 * PiRun p = new PiRun();
 * new KThread(p).fork();
 * </pre>
 *
 * </blockquote>
 */
public class KThread {
	/**
	 * Get the current thread.
	 *
	 * @return the current thread.
	 */
	public static KThread currentThread() {
		Lib.assertTrue(currentThread != null);
		return currentThread;
	}

	/**
	 * Allocate a new <tt>KThread</tt>. If this is the first <tt>KThread</tt>,
	 * create an idle thread as well.
	 */
	public KThread() {
		if (currentThread != null) {
			tcb = new TCB();
			wakeTime = 0;
		}
		else {
			readyQueue = ThreadedKernel.scheduler.newThreadQueue(false);
			readyQueue.acquire(this);

			currentThread = this;
			tcb = TCB.currentTCB();
			name = "main";
			restoreState();

			createIdleThread();
		}
	}

	/**
	 * Set the time when this thread should be woken up
	 * @param time
	 */
	public void setWakeTime(long time){
		wakeTime = time;
	}

	/**
	 * Get the time when this thread should be woken up
	 */
	public long getWakeTime(){
		return wakeTime;
	}
	/**
	 * Allocate a new KThread.
	 *
	 * @param target the object whose <tt>run</tt> method is called.
	 */
	public KThread(Runnable target) {
		this();
		this.target = target;
	}

	/**
	 * Set the target of this thread.
	 *
	 * @param target the object whose <tt>run</tt> method is called.
	 * @return this thread.
	 */
	public KThread setTarget(Runnable target) {
		Lib.assertTrue(status == statusNew);

		this.target = target;
		return this;
	}

	/**
	 * Set the name of this thread. This name is used for debugging purposes
	 * only.
	 *
	 * @param name the name to give to this thread.
	 * @return this thread.
	 */
	public KThread setName(String name) {
		this.name = name;
		return this;
	}

	/**
	 * Get the name of this thread. This name is used for debugging purposes
	 * only.
	 *
	 * @return the name given to this thread.
	 */
	public String getName() {
		return name;
	}

	/**
	 * Get the full name of this thread. This includes its name along with its
	 * numerical ID. This name is used for debugging purposes only.
	 *
	 * @return the full name given to this thread.
	 */
	public String toString() {
		return (name + " (#" + id + ")");
	}

	/**
	 * Deterministically and consistently compare this thread to another thread.
	 */
	public int compareTo(Object o) {
		KThread thread = (KThread) o;

		if (id < thread.id)
			return -1;
		else if (id > thread.id)
			return 1;
		else
			return 0;
	}

	/**
	 * Causes this thread to begin execution. The result is that two threads are
	 * running concurrently: the current thread (which returns from the call to
	 * the <tt>fork</tt> method) and the other thread (which executes its
	 * target's <tt>run</tt> method).
	 */
	public void fork() {
		Lib.assertTrue(status == statusNew);
		Lib.assertTrue(target != null);

		Lib.debug(dbgThread, "Forking thread: " + toString() + " Runnable: "
				+ target);

		boolean intStatus = Machine.interrupt().disable();

		tcb.start(new Runnable() {
			public void run() {
				runThread();
			}
		});

		ready();

		Machine.interrupt().restore(intStatus);
	}

	private void runThread() {
		begin();
		target.run();
		finish();
	}

	private void begin() {
		Lib.debug(dbgThread, "Beginning thread: " + toString());

		Lib.assertTrue(this == currentThread);

		restoreState();

		Machine.interrupt().enable();
	}

	/**
	 * Finish the current thread and schedule it to be destroyed when it is safe
	 * to do so. This method is automatically called when a thread's
	 * <tt>run</tt> method returns, but it may also be called directly.
	 *
	 * The current thread cannot be immediately destroyed because its stack and
	 * other execution state are still in use. Instead, this thread will be
	 * destroyed automatically by the next thread to run, when it is safe to
	 * delete this thread.
	 */
	public static void finish() {
		Lib.debug(dbgThread, "Finishing thread: " + currentThread.toString());

		Machine.interrupt().disable();

		Machine.autoGrader().finishingCurrentThread();

		Lib.assertTrue(toBeDestroyed == null);
		toBeDestroyed = currentThread;

		currentThread.status = statusFinished;

		//Now that this thread is finish we could give access to return from join
		KThread waiter = KThread.currentThread().threadInsideJoin.nextThread();

		//catch null pointer
		if(waiter != null) {
			//unblock thread
			waiter.ready();
		}

		sleep();
	}

	/**
	 * Relinquish the CPU if any other thread is ready to run. If so, put the
	 * current thread on the ready queue, so that it will eventually be
	 * rescheuled.
	 *
	 * <p>
	 * Returns immediately if no other thread is ready to run. Otherwise returns
	 * when the current thread is chosen to run again by
	 * <tt>readyQueue.nextThread()</tt>.
	 *
	 * <p>
	 * Interrupts are disabled, so that the current thread can atomically add
	 * itself to the ready queue and switch to the next thread. On return,
	 * restores interrupts to the previous state, in case <tt>yield()</tt> was
	 * called with interrupts disabled.
	 */
	public static void yield() {
		Lib.debug(dbgThread, "Yielding thread: " + currentThread.toString());

		Lib.assertTrue(currentThread.status == statusRunning);

		boolean intStatus = Machine.interrupt().disable();

		currentThread.ready();

		runNextThread();

		Machine.interrupt().restore(intStatus);
	}

	/**
	 * Relinquish the CPU, because the current thread has either finished or it
	 * is blocked. This thread must be the current thread.
	 *
	 * <p>
	 * If the current thread is blocked (on a synchronization primitive, i.e. a
	 * <tt>Semaphore</tt>, <tt>Lock</tt>, or <tt>Condition</tt>), eventually
	 * some thread will wake this thread up, putting it back on the ready queue
	 * so that it can be rescheduled. Otherwise, <tt>finish()</tt> should have
	 * scheduled this thread to be destroyed by the next thread to run.
	 */
	public static void sleep() {
		Lib.debug(dbgThread, "Sleeping thread: " + currentThread.toString());

		Lib.assertTrue(Machine.interrupt().disabled());

		if (currentThread.status != statusFinished)
			currentThread.status = statusBlocked;

		runNextThread();
	}

	/**
	 * Moves this thread to the ready state and adds this to the scheduler's
	 * ready queue.
	 */
	public void ready() {
		Lib.debug(dbgThread, "Ready thread: " + toString());

		Lib.assertTrue(Machine.interrupt().disabled());
		Lib.assertTrue(status != statusReady);

		status = statusReady;
		if (this != idleThread)
			readyQueue.waitForAccess(this);

		Machine.autoGrader().readyThread(this);
	}

	/**
	 * Waits for this thread to finish. If this thread is already finished,
	 * return immediately. This method must only be called once; the second call
	 * is not guaranteed to return. This thread must not be the current thread.
	 */
	public void join() {
		Lib.debug(dbgThread, "Joining to thread: " + toString());

		Lib.assertTrue(this != currentThread);

		//join can be called on a thread at most once.
		Lib.assertTrue(joinedCalled == 0);

		boolean intStatus = Machine.interrupt().disable();
		joinedCalled = 1;
		if(this.status != statusFinished){
			//caller to join is now waiting to return
			threadInsideJoin.waitForAccess(KThread.currentThread());
			currentThread.sleep();
		}
		Machine.interrupt().restore(intStatus);

	}

	/**
	 * Create the idle thread. Whenever there are no threads ready to be run,
	 * and <tt>runNextThread()</tt> is called, it will run the idle thread. The
	 * idle thread must never block, and it will only be allowed to run when all
	 * other threads are blocked.
	 *
	 * <p>
	 * Note that <tt>ready()</tt> never adds the idle thread to the ready set.
	 */
	private static void createIdleThread() {
		Lib.assertTrue(idleThread == null);

		idleThread = new KThread(new Runnable() {
			public void run() {
				while (true)
					yield();
			}
		});
		idleThread.setName("idle");

		Machine.autoGrader().setIdleThread(idleThread);

		idleThread.fork();
	}

	/**
	 * Determine the next thread to run, then dispatch the CPU to the thread
	 * using <tt>run()</tt>.
	 */
	private static void runNextThread() {
		KThread nextThread = readyQueue.nextThread();
		if (nextThread == null)
			nextThread = idleThread;

		nextThread.run();
	}

	/**
	 * Dispatch the CPU to this thread. Save the state of the current thread,
	 * switch to the new thread by calling <tt>TCB.contextSwitch()</tt>, and
	 * load the state of the new thread. The new thread becomes the current
	 * thread.
	 *
	 * <p>
	 * If the new thread and the old thread are the same, this method must still
	 * call <tt>saveState()</tt>, <tt>contextSwitch()</tt>, and
	 * <tt>restoreState()</tt>.
	 *
	 * <p>
	 * The state of the previously running thread must already have been changed
	 * from running to blocked or ready (depending on whether the thread is
	 * sleeping or yielding).
	 *
	 */
	private void run() {
		Lib.assertTrue(Machine.interrupt().disabled());

		Machine.yield();

		currentThread.saveState();

		Lib.debug(dbgThread, "Switching from: " + currentThread.toString()
				+ " to: " + toString());

		currentThread = this;

		tcb.contextSwitch();

		currentThread.restoreState();
	}

	/**
	 * Prepare this thread to be run. Set <tt>status</tt> to
	 * <tt>statusRunning</tt> and check <tt>toBeDestroyed</tt>.
	 */
	protected void restoreState() {
		Lib.debug(dbgThread, "Running thread: " + currentThread.toString());

		Lib.assertTrue(Machine.interrupt().disabled());
		Lib.assertTrue(this == currentThread);
		Lib.assertTrue(tcb == TCB.currentTCB());

		Machine.autoGrader().runningThread(this);

		status = statusRunning;

		if (toBeDestroyed != null) {
			toBeDestroyed.tcb.destroy();
			toBeDestroyed.tcb = null;
			toBeDestroyed = null;
		}
	}

	/**
	 * Prepare this thread to give up the processor. Kernel threads do not need
	 * to do anything here.
	 */
	protected void saveState() {
		Lib.assertTrue(Machine.interrupt().disabled());
		Lib.assertTrue(this == currentThread);
	}

	private static class PingTest implements Runnable {
		PingTest(int which) {
			this.which = which;
		}

		public void run() {
			for (int i = 0; i < 5; i++) {
				System.out.println("*** awesome thread " + which + " looped " + i
						+ " times");
				currentThread.yield();
			}
		}

		private int which;
	}

	/**
	 * Tests whether this module is working.
	 */
	public static void selfTest() {
		Lib.debug(dbgThread, "Enter KThread.selfTest");

		new KThread(new PingTest(1)).setName("forked thread").fork();
		new PingTest(0).run();
		joinTest1();
		joinTest2();
//		joinTest3();
		//joinTest4();
		System.out.println("==================TEST 5=====================");
		joinTest5();
		joinTest6();
		joinTest7();
	}

	private static final char dbgThread = 't';

	/**
	 * Additional state used by schedulers.
	 *
	 * @see nachos.threads.PriorityScheduler.ThreadState
	 */
	public Object schedulingState = null;

	private static final int statusNew = 0;

	private static final int statusReady = 1;

	private static final int statusRunning = 2;

	private static final int statusBlocked = 3;

	private static final int statusFinished = 4;

	/**
	 * The status of this thread. A thread can either be new (not yet forked),
	 * ready (on the ready queue but not running), running, or blocked (not on
	 * the ready queue and not running).
	 */
	private int status = statusNew;

	private String name = "(unnamed thread)";

	private Runnable target;

	private TCB tcb;

	private long wakeTime;

	/**
	 * Unique identifer for this thread. Used to deterministically compare
	 * threads.
	 */
	private int id = numCreated++;

	/** Number of times the KThread constructor was called. */
	private static int numCreated = 0;

	private static ThreadQueue readyQueue = null;

	private static KThread currentThread = null;

	private static KThread toBeDestroyed = null;

	private static KThread idleThread = null;

	// Create scheduler to limit access, specifically the right for a thread to return from join
	private ThreadQueue threadInsideJoin = ThreadedKernel.scheduler.newThreadQueue(true);

	//determines whether join was already called
	private int joinedCalled = 0;

	// Place Join test code in the KThread class and invoke test methods
	// from KThread.selfTest().

	// Simple test for the situation where the child finishes before
	// the parent calls join on it.

	private static void joinTest1 () {
		KThread child1 = new KThread( new Runnable () {
			public void run() {
				System.out.println("I (heart) Nachos!");
			}
		});
		child1.setName("child1").fork();

		// We want the child to finish before we call join.  Although
		// our solutions to the problems cannot busy wait, our test
		// programs can!

		for (int i = 0; i < 5; i++) {
			System.out.println ("busy...");
			KThread.currentThread().yield();
		}

		child1.join();
		System.out.println("After joining, child1 should be finished.");
		System.out.println("is it? " + (child1.status == statusFinished));
		Lib.assertTrue((child1.status == statusFinished), " Expected child1 to be finished.");
	}

	/**
	 * Tests that verifies if a parent calls join on a child and the child is still executing,
	 * the parent waits
	 */
	private static void joinTest2 () {
		KThread child = new KThread( new Runnable () {

			public void run() {
				System.out.println("child1 has been forked");
				//child starts but does not finish running
				for (int i = 0; i < 10; i++) {
					System.out.println ("busy... child is running");
					KThread.currentThread().yield();
				}
			}
		});

		child.setName("child").fork();
		System.out.println("Parent is running and about to call join ...");
		child.join();
		System.out.println("After joining, child1 should be finished.");
		System.out.println("is it? " + (child.status == statusFinished));
		Lib.assertTrue((child.status == statusFinished), " Expected child1 to be finished.");
	}

	/**
	 * Tests that Nachos asserts if thread calls join on itself
	 */
	private static void joinTest3 () {
		KThread.currentThread().join();
	}

	/**
	 * Test that Nachos asserts if join is called more than once on a thread
	 */
	private static void joinTest4 () {

		KThread child = new KThread( new Runnable () {

			public void run() {
				System.out.println("child1 has been forked");
				//child starts but does not finish running
				for (int i = 0; i < 3; i++) {
					System.out.println ("busy... child is running");
					KThread.currentThread().yield();
				}
			}
		});

		child.setName("child").fork();
		System.out.println("Parent is running and about to call join ...");
		//test starts here
		child.join();
		child.join();
		child.join();
		//these lines should not be executed
		System.out.println("After joining, child1 should be finished.");
		System.out.println("is it? " + (child.status == statusFinished));
		Lib.assertTrue((child.status == statusFinished), " Expected child1 to be finished.");
	}

	/**
	 * Tests if whether one parent thread can join with multiple child threads
	 * in succession
	 */
	public static void joinTest5 () {
		KThread child1 = new KThread( new Runnable () {

			public void run() {
				System.out.println("child1 has been forked");
				//child starts but does not finish running
				for (int i = 0; i < 3; i++) {
					System.out.println ("busy... child1 is running");
					KThread.currentThread().yield();
				}
			}
		});
		KThread child2 = new KThread( new Runnable () {

			public void run() {
				System.out.println("child2 has been forked");
				//child starts but does not finish running
				for (int i = 0; i < 3; i++) {
					System.out.println ("busy... child2 is running");
					KThread.currentThread().yield();
				}
			}
		});

		child1.setName("child1").fork();
		child2.setName("child2").fork();
		System.out.println("Parent is running and about to call join ...");
		//test starts here
		child1.join();
		child2.join();
		//these lines should not be executed
		System.out.println("After joining, child1 and child2 should be finished.");
		System.out.println("Are they? " + ((child1.status == statusFinished) && (child2.status == statusFinished)));
		Lib.assertTrue(((child1.status == statusFinished) && (child2.status == statusFinished)), " Expected child1 to be finished.");
	}

	/**
	 * Test if independent pairs of parent/child threads can join with each other
	 * without interference
	 */
	public static void joinTest6 () {

		//Thread A is the running process

		KThread threadB = new KThread(new Runnable() {
			@Override
			public void run() {
				//make thread B busy wait
				for (int i = 0; i < 10; i++) {
					System.out.println ("busy... Thread B is running");
					KThread.currentThread().yield();
				}
			}
		});
		System.out.println("============Test 6 Begins Thread A Init=======================");
		//fork B from A
		threadB.fork();
		//call method that forks Thread C
		joinTest6Helper(threadB);
	}

	/**
	 * Method that takes in KThread reference, starts a Thread and then join with the reference
	 */
	public static void joinTest6Helper(KThread joinable) {

		KThread threadC = new KThread(new Runnable() {
			@Override
			public void run() {
				System.out.println("Thread C will now attempt to join Thread B");
				joinable.join();
				System.out.println("Did Thread B Finish?: " + (joinable.status == statusFinished));
			}
		});

		threadC.fork();

	}

	public static void joinTest7(){
		KThread tester = new KThread(new Runnable(){
			public void run(){
				System.out.println("This test succeeded");
			}
		});
		tester.setName("tester").fork();
		tester.join();
	}
}
