/*******************************************************************************
 * This file is part of Cubic Chunks, licensed under the MIT License (MIT).
 * 
 * Copyright (c) Tall Worlds
 * Copyright (c) contributors
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 ******************************************************************************/
package cubicchunks.util;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

public class BatchedSetQueue<T> {
	
	private Set<T> setQueue;
	
	public BatchedSetQueue() {
		this.setQueue = new LinkedHashSet<T>();
	}
	
	public boolean add(T val) {
		return this.setQueue.add(val);
	}
	
	public void addAll(Iterable<T> vals) {
		for (T val : vals) {
			this.setQueue.add(val);
		}
	}
	
	public boolean contains(T val) {
		return this.setQueue.contains(val);
	}
	
	public void getBatch(Collection<T> out, int size) {
		Iterator<T> iter = this.setQueue.iterator();
		for (int i = 0; i < size && iter.hasNext(); i++) {
			out.add(iter.next());
			iter.remove();
		}
	}
	
	public int size() {
		return this.setQueue.size();
	}
}