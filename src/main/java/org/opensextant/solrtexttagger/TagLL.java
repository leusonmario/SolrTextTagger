/*
 This software was produced for the U. S. Government
 under Contract No. W15P7T-11-C-F600, and is
 subject to the Rights in Noncommercial Computer Software
 and Noncommercial Computer Software Documentation
 Clause 252.227-7014 (JUN 1995)

 Copyright 2013 The MITRE Corporation. All Rights Reserved.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package org.opensextant.solrtexttagger;

import java.io.IOException;

/**
 * This is a Tag -- a startOffset, endOffset and value.
 * <p>
 * A Tag starts without a value in an
 * "advancing" state.  {@link #advance(int, int)} is called with subsequent words
 * and then eventually it won't advance any more, and value is set (could be null).
 * <p>
 * A Tag is also a doubly-linked-list (hence the LL in the name). All tags share
 * a reference to the head via a 1-element array, which is potentially modified
 * if any of the linked-list methods are called. Tags in the list should have
 * equal or increasing start offsets.
 *
 * @author David Smiley - dsmiley@mitre.org
 */
class TagLL{

  private final TagLL[] head;//a shared pointer to the head; 1 element
  TagLL prevTag, nextTag; // linked list

  private MyFstCursor<Long> cursor;

  final int startOffset;//inclusive
  int endOffset;//exclusive
  Long value;

  /** optional boolean used by some TagClusterReducer's */
  boolean mark = false;

  TagLL(TagLL[] head, MyFstCursor<Long> cursor, int startOffset, int endOffset, Long value) {
    this.head = head;
    this.cursor = cursor;
    this.startOffset = startOffset;
    this.endOffset = endOffset;
    this.value = value;
  }

  private MyFstCursor<Long> cursor() {
    return cursor;
  }

  /**
   * Advances this tag with "word" at offset "offset".  If this tag is not in
   * an advancing state then it does nothing. If it is advancing and prior to
   * advancing further it sees a value, then a non-advancing tag may be inserted
   * into the LL as side-effect. If this returns false (it didn't advance) and
   * if there is no value, then it will also be removed.
   *
   * @param word      The next word (FST ord surrogate); not -1
   * @param offset    The last character in word's offset in the underlying
   *                  stream. If word is -1 then it's meaningless.
   *
   * @return          Whether it advanced or not.
   *
   * @throws java.io.IOException
   */
  boolean advance(int word, int offset) throws IOException {
    if (!isAdvancing())
      return false;

    Long iVal = cursor().getValue();

    if (word >= 0 && cursor().nextByLabel(word)) {

      if (iVal != null) {
        addBeforeLL(new TagLL(head, null, startOffset, endOffset, iVal));
      }

      assert offset >= endOffset;
      endOffset = offset;
      return true;
    } else {
      this.value = iVal;
      this.cursor = null;
      if (iVal == null)
        removeLL();
      return false;
    }
  }

  /** Removes this tag from the chain, connecting prevTag and nextTag. Does not modify "this" object's pointers,
   * so the caller can refer to nextTag after removing it. */
  void removeLL() {
    if (head[0] == this)
      head[0] = nextTag;
    if (prevTag != null) {
      prevTag.nextTag = nextTag;
    }
    if (nextTag != null) {
      nextTag.prevTag = prevTag;
    }
  }

  void addBeforeLL(TagLL tag) {
    assert tag.startOffset <= startOffset;
    if (prevTag != null) {
      assert prevTag.startOffset <= tag.startOffset;
      prevTag.nextTag = tag;
      tag.prevTag = prevTag;
    } else {
      assert head[0] == this;
      head[0] = tag;
    }
    prevTag = tag;
    tag.nextTag = this;
  }

  void addAfterLL(TagLL tag) {
    assert tag.startOffset >= startOffset;
    if (nextTag != null) {
      assert nextTag.startOffset >= tag.startOffset;
      nextTag.prevTag = tag;
      tag.nextTag = nextTag;
    }
    nextTag = tag;
    tag.prevTag = this;
  }

  int charLen() {
    return endOffset - startOffset;
  }

  boolean overlaps(TagLL other) {
    //don't use >= or <= because startOffset is inclusive while endOffset is exclusive
    if (startOffset < other.startOffset)
      return endOffset > other.startOffset;
    else
      return startOffset < other.endOffset;
  }

  boolean isAdvancing() {
    return cursor != null;
  }

  @Override
  public String toString() {
    return (prevTag != null ? '*' : '-') + "|" + (nextTag != null ? '*' : '-') +
        " " + startOffset + " to " + endOffset + (isAdvancing() ? '+' : " #" + value);
  }
}
