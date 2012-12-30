/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package contrail.scaffolding;

/**
 * Some utilities used for scaffolding.
 */
public class Utils {
  public static class Pair {
    public int first;
    public double second;
    public String identifier;

    public Pair(int first, double second) {
      this.first = first;
      this.second = second;
    }

    public Pair(int first, double second, String third) {
      this.first = first;
      this.second = second;
      this.identifier = third;
    }

    public int size() {
      return (Math.max(first, (int)second) - Math.min(first, (int)second) + 1);
    }
  }

  /**
   * Converts unsafe characters in read ids to safe characters.
   *
   * @param readId
   * @return
   */
  public static String safeReadId(String readId) {
    return readId.replaceAll("/", "_");
  }
}