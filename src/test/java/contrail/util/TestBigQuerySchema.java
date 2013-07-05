/* Licensed under the Apache License, Version 2.0 (the "License");
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
// Author: Jeremy Lewi (jeremy@lewi.us)
package contrail.util;

import org.junit.Test;

import contrail.graph.LengthStatsData;

public class TestBigQuerySchema {
  @Test
  public void toJsonTest() {
    BigQuerySchema schema = new BigQuerySchema();

    schema.add(new BigQueryField("Kmer", "string"));
    schema.add(new BigQueryField("before", "integer"));
    schema.add(new BigQueryField("after", "integer"));

    String json = schema.toJson();

    System.out.println(json);
  }

  @Test
  public void fromAvroSchema() {
    LengthStatsData statsData = new LengthStatsData();
    BigQuerySchema schema = BigQuerySchema.fromAvroSchema(
        statsData.getSchema());

    String json = schema.toJson();
    System.out.println(json);
  }
}