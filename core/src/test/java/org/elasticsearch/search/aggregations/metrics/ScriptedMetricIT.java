/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.aggregations.metrics;

import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.env.Environment;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.script.MockScriptPlugin;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.bucket.global.Global;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram.Bucket;
import org.elasticsearch.search.aggregations.metrics.scripted.ScriptedMetric;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;
import org.elasticsearch.test.ESIntegTestCase.Scope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.search.aggregations.AggregationBuilders.global;
import static org.elasticsearch.search.aggregations.AggregationBuilders.histogram;
import static org.elasticsearch.search.aggregations.AggregationBuilders.scriptedMetric;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertSearchResponse;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;

@ClusterScope(scope = Scope.SUITE)
@ESIntegTestCase.SuiteScopeTestCase
public class ScriptedMetricIT extends ESIntegTestCase {

    private static long numDocs;

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Collections.singleton(CustomScriptPlugin.class);
    }

    public static class CustomScriptPlugin extends MockScriptPlugin {

        @Override
        @SuppressWarnings("unchecked")
        protected Map<String, Function<Map<String, Object>, Object>> pluginScripts() {
            Map<String, Function<Map<String, Object>, Object>> scripts = new HashMap<>();

            scripts.put("_agg['count'] = 1", vars ->
                    aggScript(vars, agg -> ((Map<String, Object>) agg).put("count", 1)));

            scripts.put("_agg.add(1)", vars ->
                    aggScript(vars, agg -> ((List) agg).add(1)));

            scripts.put("vars.multiplier = 3", vars ->
                    ((Map<String, Object>) vars.get("vars")).put("multiplier", 3));

            scripts.put("_agg.add(vars.multiplier)", vars ->
                    aggScript(vars, agg -> ((List) agg).add(XContentMapValues.extractValue("vars.multiplier", vars))));

            // Equivalent to:
            //
            // newaggregation = [];
            // sum = 0;
            //
            // for (a in _agg) {
            //      sum += a
            // };
            //
            // newaggregation.add(sum);
            // return newaggregation"
            //
            scripts.put("sum agg values as a new aggregation", vars -> {
                List newAggregation = new ArrayList();
                List<?> agg = (List<?>) vars.get("_agg");

                if (agg != null) {
                    Integer sum = 0;
                    for (Object a : (List) agg) {
                        sum += ((Number) a).intValue();
                    }
                    newAggregation.add(sum);
                }
                return newAggregation;
            });

            // Equivalent to:
            //
            // newaggregation = [];
            // sum = 0;
            //
            // for (aggregation in _aggs) {
            //      for (a in aggregation) {
            //          sum += a
            //      }
            // };
            //
            // newaggregation.add(sum);
            // return newaggregation"
            //
            scripts.put("sum aggs of agg values as a new aggregation", vars -> {
                List newAggregation = new ArrayList();
                Integer sum = 0;

                List<?> aggs = (List<?>) vars.get("_aggs");
                for (Object aggregation : (List) aggs) {
                    if (aggregation != null) {
                        for (Object a : (List) aggregation) {
                            sum += ((Number) a).intValue();
                        }
                    }
                }
                newAggregation.add(sum);
                return newAggregation;
            });

            // Equivalent to:
            //
            // newaggregation = [];
            // sum = 0;
            //
            // for (aggregation in _aggs) {
            //      for (a in aggregation) {
            //          sum += a
            //      }
            // };
            //
            // newaggregation.add(sum * multiplier);
            // return newaggregation"
            //
            scripts.put("multiplied sum aggs of agg values as a new aggregation", vars -> {
                Integer multiplier = (Integer) vars.get("multiplier");
                List newAggregation = new ArrayList();
                Integer sum = 0;

                List<?> aggs = (List<?>) vars.get("_aggs");
                for (Object aggregation : (List) aggs) {
                    if (aggregation != null) {
                        for (Object a : (List) aggregation) {
                            sum += ((Number) a).intValue();
                        }
                    }
                }
                newAggregation.add(sum * multiplier);
                return newAggregation;
            });

            return scripts;
        }

        @SuppressWarnings("unchecked")
        static <T> Object aggScript(Map<String, Object> vars, Consumer<T> fn) {
            T agg = (T) vars.get("_agg");
            fn.accept(agg);
            return agg;
        }
    }

    @Override
    public void setupSuiteScopeCluster() throws Exception {
        createIndex("idx");

        List<IndexRequestBuilder> builders = new ArrayList<>();

        numDocs = randomIntBetween(10, 100);
        for (int i = 0; i < numDocs; i++) {
            builders.add(client().prepareIndex("idx", "type", "" + i).setSource(
                    jsonBuilder().startObject().field("value", randomAlphaOfLengthBetween(5, 15))
                            .field("l_value", i).endObject()));
        }
        indexRandom(true, builders);

        // creating an index to test the empty buckets functionality. The way it
        // works is by indexing
        // two docs {value: 0} and {value : 2}, then building a histogram agg
        // with interval 1 and with empty
        // buckets computed.. the empty bucket is the one associated with key
        // "1". then each test will have
        // to check that this bucket exists with the appropriate sub
        // aggregations.
        prepareCreate("empty_bucket_idx").addMapping("type", "value", "type=integer").execute().actionGet();
        builders = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            builders.add(client().prepareIndex("empty_bucket_idx", "type", "" + i).setSource(
                    jsonBuilder().startObject().field("value", i * 2).endObject()));
        }

        // When using the MockScriptPlugin we can map Stored scripts to inline scripts:
        // the id of the stored script is used in test method while the source of the stored script
        // must match a predefined script from CustomScriptPlugin.pluginScripts() method
        assertAcked(client().admin().cluster().preparePutStoredScript()
                .setLang(CustomScriptPlugin.NAME)
                .setId("initScript_stored")
                .setContent(new BytesArray("{\"script\":\"vars.multiplier = 3\"}"), XContentType.JSON));

        assertAcked(client().admin().cluster().preparePutStoredScript()
                .setLang(CustomScriptPlugin.NAME)
                .setId("mapScript_stored")
                .setContent(new BytesArray("{\"script\":\"_agg.add(vars.multiplier)\"}"), XContentType.JSON));

        assertAcked(client().admin().cluster().preparePutStoredScript()
                .setLang(CustomScriptPlugin.NAME)
                .setId("combineScript_stored")
                .setContent(new BytesArray("{\"script\":\"sum agg values as a new aggregation\"}"), XContentType.JSON));

        assertAcked(client().admin().cluster().preparePutStoredScript()
                .setLang(CustomScriptPlugin.NAME)
                .setId("reduceScript_stored")
                .setContent(new BytesArray("{\"script\":\"sum aggs of agg values as a new aggregation\"}"), XContentType.JSON));

        indexRandom(true, builders);
        ensureSearchable();
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        Path config = createTempDir().resolve("config");
        Path scripts = config.resolve("scripts");

        try {
            Files.createDirectories(scripts);

            // When using the MockScriptPlugin we can map File scripts to inline scripts:
            // the name of the file script is used in test method while the source of the file script
            // must match a predefined script from CustomScriptPlugin.pluginScripts() method
            Files.write(scripts.resolve("init_script.mockscript"), "vars.multiplier = 3".getBytes("UTF-8"));
            Files.write(scripts.resolve("map_script.mockscript"), "_agg.add(vars.multiplier)".getBytes("UTF-8"));
            Files.write(scripts.resolve("combine_script.mockscript"), "sum agg values as a new aggregation".getBytes("UTF-8"));
            Files.write(scripts.resolve("reduce_script.mockscript"), "sum aggs of agg values as a new aggregation".getBytes("UTF-8"));
        } catch (IOException e) {
            throw new RuntimeException("failed to create scripts");
        }

        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put(Environment.PATH_CONF_SETTING.getKey(), config)
                .build();
    }

    public void testMap() {
        Script mapScript = new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "_agg['count'] = 1", Collections.emptyMap());

        SearchResponse response = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(scriptedMetric("scripted").mapScript(mapScript))
                .get();
        assertSearchResponse(response);
        assertThat(response.getHits().getTotalHits(), equalTo(numDocs));

        Aggregation aggregation = response.getAggregations().get("scripted");
        assertThat(aggregation, notNullValue());
        assertThat(aggregation, instanceOf(ScriptedMetric.class));
        ScriptedMetric scriptedMetricAggregation = (ScriptedMetric) aggregation;
        assertThat(scriptedMetricAggregation.getName(), equalTo("scripted"));
        assertThat(scriptedMetricAggregation.aggregation(), notNullValue());
        assertThat(scriptedMetricAggregation.aggregation(), instanceOf(ArrayList.class));
        List<?> aggregationList = (List<?>) scriptedMetricAggregation.aggregation();
        assertThat(aggregationList.size(), equalTo(getNumShards("idx").numPrimaries));
        int numShardsRun = 0;
        for (Object object : aggregationList) {
            assertThat(object, notNullValue());
            assertThat(object, instanceOf(Map.class));
            Map<?, ?> map = (Map<?, ?>) object;
            assertThat(map.size(), lessThanOrEqualTo(1));
            if (map.size() == 1) {
                assertThat(map.get("count"), notNullValue());
                assertThat(map.get("count"), instanceOf(Number.class));
                assertThat(map.get("count"), equalTo((Number) 1));
                numShardsRun++;
            }
        }
        // We don't know how many shards will have documents but we need to make
        // sure that at least one shard ran the map script
        assertThat(numShardsRun, greaterThan(0));
    }

    public void testMapWithParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("_agg", new ArrayList<>());

        Script mapScript = new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "_agg.add(1)", params);

        SearchResponse response = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(scriptedMetric("scripted").params(params).mapScript(mapScript))
                .get();
        assertSearchResponse(response);
        assertThat(response.getHits().getTotalHits(), equalTo(numDocs));

        Aggregation aggregation = response.getAggregations().get("scripted");
        assertThat(aggregation, notNullValue());
        assertThat(aggregation, instanceOf(ScriptedMetric.class));
        ScriptedMetric scriptedMetricAggregation = (ScriptedMetric) aggregation;
        assertThat(scriptedMetricAggregation.getName(), equalTo("scripted"));
        assertThat(scriptedMetricAggregation.aggregation(), notNullValue());
        assertThat(scriptedMetricAggregation.aggregation(), instanceOf(ArrayList.class));
        List<?> aggregationList = (List<?>) scriptedMetricAggregation.aggregation();
        assertThat(aggregationList.size(), equalTo(getNumShards("idx").numPrimaries));
        long totalCount = 0;
        for (Object object : aggregationList) {
            assertThat(object, notNullValue());
            assertThat(object, instanceOf(List.class));
            List<?> list = (List<?>) object;
            for (Object o : list) {
                assertThat(o, notNullValue());
                assertThat(o, instanceOf(Number.class));
                Number numberValue = (Number) o;
                assertThat(numberValue, equalTo((Number) 1));
                totalCount += numberValue.longValue();
            }
        }
        assertThat(totalCount, equalTo(numDocs));
    }

    public void testInitMapWithParams() {
        Map<String, Object> varsMap = new HashMap<>();
        varsMap.put("multiplier", 1);

        Map<String, Object> params = new HashMap<>();
        params.put("_agg", new ArrayList<>());
        params.put("vars", varsMap);

        SearchResponse response = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        scriptedMetric("scripted")
                                .params(params)
                                .initScript(
                                    new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "vars.multiplier = 3", Collections.emptyMap()))
                                .mapScript(new Script(ScriptType.INLINE, CustomScriptPlugin.NAME,
                                    "_agg.add(vars.multiplier)", Collections.emptyMap())))
                .get();
        assertSearchResponse(response);
        assertThat(response.getHits().getTotalHits(), equalTo(numDocs));

        Aggregation aggregation = response.getAggregations().get("scripted");
        assertThat(aggregation, notNullValue());
        assertThat(aggregation, instanceOf(ScriptedMetric.class));
        ScriptedMetric scriptedMetricAggregation = (ScriptedMetric) aggregation;
        assertThat(scriptedMetricAggregation.getName(), equalTo("scripted"));
        assertThat(scriptedMetricAggregation.aggregation(), notNullValue());
        assertThat(scriptedMetricAggregation.aggregation(), instanceOf(ArrayList.class));
        List<?> aggregationList = (List<?>) scriptedMetricAggregation.aggregation();
        assertThat(aggregationList.size(), equalTo(getNumShards("idx").numPrimaries));
        long totalCount = 0;
        for (Object object : aggregationList) {
            assertThat(object, notNullValue());
            assertThat(object, instanceOf(List.class));
            List<?> list = (List<?>) object;
            for (Object o : list) {
                assertThat(o, notNullValue());
                assertThat(o, instanceOf(Number.class));
                Number numberValue = (Number) o;
                assertThat(numberValue, equalTo((Number) 3));
                totalCount += numberValue.longValue();
            }
        }
        assertThat(totalCount, equalTo(numDocs * 3));
    }

    public void testMapCombineWithParams() {
        Map<String, Object> varsMap = new HashMap<>();
        varsMap.put("multiplier", 1);

        Map<String, Object> params = new HashMap<>();
        params.put("_agg", new ArrayList<>());
        params.put("vars", varsMap);

        Script mapScript = new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "_agg.add(1)", Collections.emptyMap());
        Script combineScript =
            new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "sum agg values as a new aggregation", Collections.emptyMap());

        SearchResponse response = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        scriptedMetric("scripted")
                                .params(params)
                                .mapScript(mapScript)
                                .combineScript(combineScript))
                .execute().actionGet();
        assertSearchResponse(response);
        assertThat(response.getHits().getTotalHits(), equalTo(numDocs));

        Aggregation aggregation = response.getAggregations().get("scripted");
        assertThat(aggregation, notNullValue());
        assertThat(aggregation, instanceOf(ScriptedMetric.class));
        ScriptedMetric scriptedMetricAggregation = (ScriptedMetric) aggregation;
        assertThat(scriptedMetricAggregation.getName(), equalTo("scripted"));
        assertThat(scriptedMetricAggregation.aggregation(), notNullValue());
        assertThat(scriptedMetricAggregation.aggregation(), instanceOf(ArrayList.class));
        List<?> aggregationList = (List<?>) scriptedMetricAggregation.aggregation();
        assertThat(aggregationList.size(), equalTo(getNumShards("idx").numPrimaries));
        long totalCount = 0;
        for (Object object : aggregationList) {
            assertThat(object, notNullValue());
            assertThat(object, instanceOf(List.class));
            List<?> list = (List<?>) object;
            for (Object o : list) {
                assertThat(o, notNullValue());
                assertThat(o, instanceOf(Number.class));
                Number numberValue = (Number) o;
                // A particular shard may not have any documents stored on it so
                // we have to assume the lower bound may be 0. The check at the
                // bottom of the test method will make sure the count is correct
                assertThat(numberValue.longValue(), allOf(greaterThanOrEqualTo(0L), lessThanOrEqualTo(numDocs)));
                totalCount += numberValue.longValue();
            }
        }
        assertThat(totalCount, equalTo(numDocs));
    }

    public void testInitMapCombineWithParams() {
        Map<String, Object> varsMap = new HashMap<>();
        varsMap.put("multiplier", 1);

        Map<String, Object> params = new HashMap<>();
        params.put("_agg", new ArrayList<>());
        params.put("vars", varsMap);

        Script initScript = new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "vars.multiplier = 3", Collections.emptyMap());
        Script mapScript = new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "_agg.add(vars.multiplier)", Collections.emptyMap());
        Script combineScript =
            new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "sum agg values as a new aggregation", Collections.emptyMap());

        SearchResponse response = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        scriptedMetric("scripted")
                                .params(params)
                                .initScript(initScript)
                                .mapScript(mapScript)
                                .combineScript(combineScript))
                .get();
        assertSearchResponse(response);
        assertThat(response.getHits().getTotalHits(), equalTo(numDocs));

        Aggregation aggregation = response.getAggregations().get("scripted");
        assertThat(aggregation, notNullValue());
        assertThat(aggregation, instanceOf(ScriptedMetric.class));
        ScriptedMetric scriptedMetricAggregation = (ScriptedMetric) aggregation;
        assertThat(scriptedMetricAggregation.getName(), equalTo("scripted"));
        assertThat(scriptedMetricAggregation.aggregation(), notNullValue());
        assertThat(scriptedMetricAggregation.aggregation(), instanceOf(ArrayList.class));
        List<?> aggregationList = (List<?>) scriptedMetricAggregation.aggregation();
        assertThat(aggregationList.size(), equalTo(getNumShards("idx").numPrimaries));
        long totalCount = 0;
        for (Object object : aggregationList) {
            assertThat(object, notNullValue());
            assertThat(object, instanceOf(List.class));
            List<?> list = (List<?>) object;
            for (Object o : list) {
                assertThat(o, notNullValue());
                assertThat(o, instanceOf(Number.class));
                Number numberValue = (Number) o;
                // A particular shard may not have any documents stored on it so
                // we have to assume the lower bound may be 0. The check at the
                // bottom of the test method will make sure the count is correct
                assertThat(numberValue.longValue(), allOf(greaterThanOrEqualTo(0L), lessThanOrEqualTo(numDocs * 3)));
                totalCount += numberValue.longValue();
            }
        }
        assertThat(totalCount, equalTo(numDocs * 3));
    }

    public void testInitMapCombineReduceWithParams() {
        Map<String, Object> varsMap = new HashMap<>();
        varsMap.put("multiplier", 1);

        Map<String, Object> params = new HashMap<>();
        params.put("_agg", new ArrayList<>());
        params.put("vars", varsMap);

        Script initScript = new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "vars.multiplier = 3", Collections.emptyMap());
        Script mapScript = new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "_agg.add(vars.multiplier)", Collections.emptyMap());
        Script combineScript =
            new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "sum agg values as a new aggregation", Collections.emptyMap());
        Script reduceScript =
            new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "sum aggs of agg values as a new aggregation", Collections.emptyMap());

        SearchResponse response = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        scriptedMetric("scripted")
                                .params(params)
                                .initScript(initScript)
                                .mapScript(mapScript)
                                .combineScript(combineScript)
                                .reduceScript(reduceScript))
                .get();
        assertSearchResponse(response);
        assertThat(response.getHits().getTotalHits(), equalTo(numDocs));

        Aggregation aggregation = response.getAggregations().get("scripted");
        assertThat(aggregation, notNullValue());
        assertThat(aggregation, instanceOf(ScriptedMetric.class));
        ScriptedMetric scriptedMetricAggregation = (ScriptedMetric) aggregation;
        assertThat(scriptedMetricAggregation.getName(), equalTo("scripted"));
        assertThat(scriptedMetricAggregation.aggregation(), notNullValue());
        assertThat(scriptedMetricAggregation.aggregation(), instanceOf(ArrayList.class));
        List<?> aggregationList = (List<?>) scriptedMetricAggregation.aggregation();
        assertThat(aggregationList.size(), equalTo(1));
        Object object = aggregationList.get(0);
        assertThat(object, notNullValue());
        assertThat(object, instanceOf(Number.class));
        assertThat(((Number) object).longValue(), equalTo(numDocs * 3));
    }

    @SuppressWarnings("rawtypes")
    public void testInitMapCombineReduceGetProperty() throws Exception {
        Map<String, Object> varsMap = new HashMap<>();
        varsMap.put("multiplier", 1);

        Map<String, Object> params = new HashMap<>();
        params.put("_agg", new ArrayList<>());
        params.put("vars", varsMap);

        Script initScript = new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "vars.multiplier = 3", Collections.emptyMap());
        Script mapScript = new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "_agg.add(vars.multiplier)", Collections.emptyMap());
        Script combineScript =
            new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "sum agg values as a new aggregation", Collections.emptyMap());
        Script reduceScript =
            new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "sum aggs of agg values as a new aggregation", Collections.emptyMap());

        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        global("global")
                                .subAggregation(
                                        scriptedMetric("scripted")
                                                .params(params)
                                                .initScript(initScript)
                                                .mapScript(mapScript)
                                                .combineScript(combineScript)
                                                .reduceScript(reduceScript)))
                .get();

        assertSearchResponse(searchResponse);
        assertThat(searchResponse.getHits().getTotalHits(), equalTo(numDocs));

        Global global = searchResponse.getAggregations().get("global");
        assertThat(global, notNullValue());
        assertThat(global.getName(), equalTo("global"));
        assertThat(global.getDocCount(), equalTo(numDocs));
        assertThat(global.getAggregations(), notNullValue());
        assertThat(global.getAggregations().asMap().size(), equalTo(1));

        ScriptedMetric scriptedMetricAggregation = global.getAggregations().get("scripted");
        assertThat(scriptedMetricAggregation, notNullValue());
        assertThat(scriptedMetricAggregation.getName(), equalTo("scripted"));
        assertThat(scriptedMetricAggregation.aggregation(), notNullValue());
        assertThat(scriptedMetricAggregation.aggregation(), instanceOf(ArrayList.class));
        List<?> aggregationList = (List<?>) scriptedMetricAggregation.aggregation();
        assertThat(aggregationList.size(), equalTo(1));
        Object object = aggregationList.get(0);
        assertThat(object, notNullValue());
        assertThat(object, instanceOf(Number.class));
        assertThat(((Number) object).longValue(), equalTo(numDocs * 3));
        assertThat(((InternalAggregation)global).getProperty("scripted"), sameInstance(scriptedMetricAggregation));
        assertThat((List) ((InternalAggregation)global).getProperty("scripted.value"), sameInstance((List) aggregationList));
        assertThat((List) ((InternalAggregation)scriptedMetricAggregation).getProperty("value"), sameInstance((List) aggregationList));
    }

    public void testMapCombineReduceWithParams() {
        Map<String, Object> varsMap = new HashMap<>();
        varsMap.put("multiplier", 1);

        Map<String, Object> params = new HashMap<>();
        params.put("_agg", new ArrayList<>());
        params.put("vars", varsMap);

        Script mapScript = new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "_agg.add(vars.multiplier)", Collections.emptyMap());
        Script combineScript =
            new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "sum agg values as a new aggregation", Collections.emptyMap());
        Script reduceScript =
            new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "sum aggs of agg values as a new aggregation", Collections.emptyMap());

        SearchResponse response = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        scriptedMetric("scripted")
                                .params(params)
                                .mapScript(mapScript)
                                .combineScript(combineScript)
                                .reduceScript(reduceScript))
                .get();
        assertSearchResponse(response);
        assertThat(response.getHits().getTotalHits(), equalTo(numDocs));

        Aggregation aggregation = response.getAggregations().get("scripted");
        assertThat(aggregation, notNullValue());
        assertThat(aggregation, instanceOf(ScriptedMetric.class));
        ScriptedMetric scriptedMetricAggregation = (ScriptedMetric) aggregation;
        assertThat(scriptedMetricAggregation.getName(), equalTo("scripted"));
        assertThat(scriptedMetricAggregation.aggregation(), notNullValue());
        assertThat(scriptedMetricAggregation.aggregation(), instanceOf(ArrayList.class));
        List<?> aggregationList = (List<?>) scriptedMetricAggregation.aggregation();
        assertThat(aggregationList.size(), equalTo(1));
        Object object = aggregationList.get(0);
        assertThat(object, notNullValue());
        assertThat(object, instanceOf(Number.class));
        assertThat(((Number) object).longValue(), equalTo(numDocs));
    }

    public void testInitMapReduceWithParams() {
        Map<String, Object> varsMap = new HashMap<>();
        varsMap.put("multiplier", 1);

        Map<String, Object> params = new HashMap<>();
        params.put("_agg", new ArrayList<>());
        params.put("vars", varsMap);

        Script initScript = new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "vars.multiplier = 3", Collections.emptyMap());
        Script mapScript = new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "_agg.add(vars.multiplier)", Collections.emptyMap());
        Script reduceScript =
            new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "sum aggs of agg values as a new aggregation", Collections.emptyMap());

        SearchResponse response = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        scriptedMetric("scripted")
                                .params(params)
                                .initScript(initScript)
                                .mapScript(mapScript)
                                .reduceScript(reduceScript))
                .get();
        assertSearchResponse(response);
        assertThat(response.getHits().getTotalHits(), equalTo(numDocs));

        Aggregation aggregation = response.getAggregations().get("scripted");
        assertThat(aggregation, notNullValue());
        assertThat(aggregation, instanceOf(ScriptedMetric.class));
        ScriptedMetric scriptedMetricAggregation = (ScriptedMetric) aggregation;
        assertThat(scriptedMetricAggregation.getName(), equalTo("scripted"));
        assertThat(scriptedMetricAggregation.aggregation(), notNullValue());
        assertThat(scriptedMetricAggregation.aggregation(), instanceOf(ArrayList.class));
        List<?> aggregationList = (List<?>) scriptedMetricAggregation.aggregation();
        assertThat(aggregationList.size(), equalTo(1));
        Object object = aggregationList.get(0);
        assertThat(object, notNullValue());
        assertThat(object, instanceOf(Number.class));
        assertThat(((Number) object).longValue(), equalTo(numDocs * 3));
    }

    public void testMapReduceWithParams() {
        Map<String, Object> varsMap = new HashMap<>();
        varsMap.put("multiplier", 1);
        Map<String, Object> params = new HashMap<>();
        params.put("_agg", new ArrayList<>());
        params.put("vars", varsMap);

        Script mapScript = new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "_agg.add(vars.multiplier)", Collections.emptyMap());
        Script reduceScript =
            new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "sum aggs of agg values as a new aggregation", Collections.emptyMap());

        SearchResponse response = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        scriptedMetric("scripted")
                                .params(params)
                                .mapScript(mapScript)
                                .reduceScript(reduceScript))
                .get();
        assertSearchResponse(response);
        assertThat(response.getHits().getTotalHits(), equalTo(numDocs));

        Aggregation aggregation = response.getAggregations().get("scripted");
        assertThat(aggregation, notNullValue());
        assertThat(aggregation, instanceOf(ScriptedMetric.class));
        ScriptedMetric scriptedMetricAggregation = (ScriptedMetric) aggregation;
        assertThat(scriptedMetricAggregation.getName(), equalTo("scripted"));
        assertThat(scriptedMetricAggregation.aggregation(), notNullValue());
        assertThat(scriptedMetricAggregation.aggregation(), instanceOf(ArrayList.class));
        List<?> aggregationList = (List<?>) scriptedMetricAggregation.aggregation();
        assertThat(aggregationList.size(), equalTo(1));
        Object object = aggregationList.get(0);
        assertThat(object, notNullValue());
        assertThat(object, instanceOf(Number.class));
        assertThat(((Number) object).longValue(), equalTo(numDocs));
    }

    public void testInitMapCombineReduceWithParamsAndReduceParams() {
        Map<String, Object> varsMap = new HashMap<>();
        varsMap.put("multiplier", 1);

        Map<String, Object> params = new HashMap<>();
        params.put("_agg", new ArrayList<>());
        params.put("vars", varsMap);

        Map<String, Object> reduceParams = new HashMap<>();
        reduceParams.put("multiplier", 4);

        Script initScript = new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "vars.multiplier = 3", Collections.emptyMap());
        Script mapScript = new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "_agg.add(vars.multiplier)", Collections.emptyMap());
        Script combineScript =
            new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "sum agg values as a new aggregation", Collections.emptyMap());
        Script reduceScript =
            new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "multiplied sum aggs of agg values as a new aggregation", reduceParams);

        SearchResponse response = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        scriptedMetric("scripted")
                                .params(params)
                                .initScript(initScript)
                                .mapScript(mapScript)
                                .combineScript(combineScript)
                                .reduceScript(reduceScript))
                .execute().actionGet();
        assertSearchResponse(response);
        assertThat(response.getHits().getTotalHits(), equalTo(numDocs));

        Aggregation aggregation = response.getAggregations().get("scripted");
        assertThat(aggregation, notNullValue());
        assertThat(aggregation, instanceOf(ScriptedMetric.class));
        ScriptedMetric scriptedMetricAggregation = (ScriptedMetric) aggregation;
        assertThat(scriptedMetricAggregation.getName(), equalTo("scripted"));
        assertThat(scriptedMetricAggregation.aggregation(), notNullValue());
        assertThat(scriptedMetricAggregation.aggregation(), instanceOf(ArrayList.class));
        List<?> aggregationList = (List<?>) scriptedMetricAggregation.aggregation();
        assertThat(aggregationList.size(), equalTo(1));
        Object object = aggregationList.get(0);
        assertThat(object, notNullValue());
        assertThat(object, instanceOf(Number.class));
        assertThat(((Number) object).longValue(), equalTo(numDocs * 12));
    }

    public void testInitMapCombineReduceWithParamsStored() {
        Map<String, Object> varsMap = new HashMap<>();
        varsMap.put("multiplier", 1);

        Map<String, Object> params = new HashMap<>();
        params.put("_agg", new ArrayList<>());
        params.put("vars", varsMap);

        SearchResponse response = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        scriptedMetric("scripted")
                                .params(params)
                                .initScript(
                                    new Script(ScriptType.STORED, CustomScriptPlugin.NAME, "initScript_stored", Collections.emptyMap()))
                                .mapScript(
                                    new Script(ScriptType.STORED, CustomScriptPlugin.NAME, "mapScript_stored", Collections.emptyMap()))
                                .combineScript(
                                    new Script(ScriptType.STORED, CustomScriptPlugin.NAME, "combineScript_stored", Collections.emptyMap()))
                                .reduceScript(
                                    new Script(ScriptType.STORED, CustomScriptPlugin.NAME, "reduceScript_stored", Collections.emptyMap())))
                .get();
        assertSearchResponse(response);
        assertThat(response.getHits().getTotalHits(), equalTo(numDocs));

        Aggregation aggregation = response.getAggregations().get("scripted");
        assertThat(aggregation, notNullValue());
        assertThat(aggregation, instanceOf(ScriptedMetric.class));
        ScriptedMetric scriptedMetricAggregation = (ScriptedMetric) aggregation;
        assertThat(scriptedMetricAggregation.getName(), equalTo("scripted"));
        assertThat(scriptedMetricAggregation.aggregation(), notNullValue());
        assertThat(scriptedMetricAggregation.aggregation(), instanceOf(ArrayList.class));
        List<?> aggregationList = (List<?>) scriptedMetricAggregation.aggregation();
        assertThat(aggregationList.size(), equalTo(1));
        Object object = aggregationList.get(0);
        assertThat(object, notNullValue());
        assertThat(object, instanceOf(Number.class));
        assertThat(((Number) object).longValue(), equalTo(numDocs * 3));
    }

    public void testInitMapCombineReduceWithParamsAsSubAgg() {
        Map<String, Object> varsMap = new HashMap<>();
        varsMap.put("multiplier", 1);

        Map<String, Object> params = new HashMap<>();
        params.put("_agg", new ArrayList<>());
        params.put("vars", varsMap);

        Script initScript = new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "vars.multiplier = 3", Collections.emptyMap());
        Script mapScript = new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "_agg.add(vars.multiplier)", Collections.emptyMap());
        Script combineScript =
            new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "sum agg values as a new aggregation", Collections.emptyMap());
        Script reduceScript =
            new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "sum aggs of agg values as a new aggregation", Collections.emptyMap());

        SearchResponse response = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery()).setSize(1000)
                .addAggregation(
                        histogram("histo")
                                .field("l_value")
                                .interval(1)
                                .subAggregation(
                                        scriptedMetric("scripted")
                                                .params(params)
                                                .initScript(initScript)
                                                .mapScript(mapScript)
                                                .combineScript(combineScript)
                                                .reduceScript(reduceScript)))
                .get();
        assertSearchResponse(response);
        assertThat(response.getHits().getTotalHits(), equalTo(numDocs));
        Aggregation aggregation = response.getAggregations().get("histo");
        assertThat(aggregation, notNullValue());
        assertThat(aggregation, instanceOf(Histogram.class));
        Histogram histoAgg = (Histogram) aggregation;
        assertThat(histoAgg.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histoAgg.getBuckets();
        assertThat(buckets, notNullValue());
        for (Bucket b : buckets) {
            assertThat(b, notNullValue());
            assertThat(b.getDocCount(), equalTo(1L));
            Aggregations subAggs = b.getAggregations();
            assertThat(subAggs, notNullValue());
            assertThat(subAggs.asList().size(), equalTo(1));
            Aggregation subAgg = subAggs.get("scripted");
            assertThat(subAgg, notNullValue());
            assertThat(subAgg, instanceOf(ScriptedMetric.class));
            ScriptedMetric scriptedMetricAggregation = (ScriptedMetric) subAgg;
            assertThat(scriptedMetricAggregation.getName(), equalTo("scripted"));
            assertThat(scriptedMetricAggregation.aggregation(), notNullValue());
            assertThat(scriptedMetricAggregation.aggregation(), instanceOf(ArrayList.class));
            List<?> aggregationList = (List<?>) scriptedMetricAggregation.aggregation();
            assertThat(aggregationList.size(), equalTo(1));
            Object object = aggregationList.get(0);
            assertThat(object, notNullValue());
            assertThat(object, instanceOf(Number.class));
            assertThat(((Number) object).longValue(), equalTo(3L));
        }
    }

    public void testEmptyAggregation() throws Exception {
        Map<String, Object> varsMap = new HashMap<>();
        varsMap.put("multiplier", 1);

        Map<String, Object> params = new HashMap<>();
        params.put("_agg", new ArrayList<>());
        params.put("vars", varsMap);

        Script initScript = new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "vars.multiplier = 3", Collections.emptyMap());
        Script mapScript = new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "_agg.add(vars.multiplier)", Collections.emptyMap());
        Script combineScript =
            new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "sum agg values as a new aggregation", Collections.emptyMap());
        Script reduceScript =
            new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "sum aggs of agg values as a new aggregation", Collections.emptyMap());

        SearchResponse searchResponse = client().prepareSearch("empty_bucket_idx")
                .setQuery(matchAllQuery())
                .addAggregation(histogram("histo").field("value").interval(1L).minDocCount(0)
                        .subAggregation(
                                scriptedMetric("scripted")
                                        .params(params)
                                        .initScript(initScript)
                                        .mapScript(mapScript)
                                        .combineScript(combineScript)
                                        .reduceScript(reduceScript)))
                .get();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(2L));
        Histogram histo = searchResponse.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        Histogram.Bucket bucket = histo.getBuckets().get(1);
        assertThat(bucket, notNullValue());

        ScriptedMetric scriptedMetric = bucket.getAggregations().get("scripted");
        assertThat(scriptedMetric, notNullValue());
        assertThat(scriptedMetric.getName(), equalTo("scripted"));
        assertThat(scriptedMetric.aggregation(), notNullValue());
        assertThat(scriptedMetric.aggregation(), instanceOf(List.class));
        @SuppressWarnings("unchecked") // We'll just get a ClassCastException a couple lines down if we're wrong, its ok.
        List<Integer> aggregationResult = (List<Integer>) scriptedMetric.aggregation();
        assertThat(aggregationResult.size(), equalTo(1));
        assertThat(aggregationResult.get(0), equalTo(0));
    }

    /**
     * Make sure that a request using a script does not get cached and a request
     * not using a script does get cached.
     */
    public void testDontCacheScripts() throws Exception {
        Script mapScript = new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "_agg['count'] = 1", Collections.emptyMap());
        assertAcked(prepareCreate("cache_test_idx").addMapping("type", "d", "type=long")
                .setSettings(Settings.builder().put("requests.cache.enable", true).put("number_of_shards", 1).put("number_of_replicas", 1))
                .get());
        indexRandom(true, client().prepareIndex("cache_test_idx", "type", "1").setSource("s", 1),
                client().prepareIndex("cache_test_idx", "type", "2").setSource("s", 2));

        // Make sure we are starting with a clear cache
        assertThat(client().admin().indices().prepareStats("cache_test_idx").setRequestCache(true).get().getTotal().getRequestCache()
                .getHitCount(), equalTo(0L));
        assertThat(client().admin().indices().prepareStats("cache_test_idx").setRequestCache(true).get().getTotal().getRequestCache()
                .getMissCount(), equalTo(0L));

        // Test that a request using a script does not get cached
        SearchResponse r = client().prepareSearch("cache_test_idx").setSize(0)
                .addAggregation(scriptedMetric("foo").mapScript(mapScript)).get();
        assertSearchResponse(r);

        assertThat(client().admin().indices().prepareStats("cache_test_idx").setRequestCache(true).get().getTotal().getRequestCache()
                .getHitCount(), equalTo(0L));
        assertThat(client().admin().indices().prepareStats("cache_test_idx").setRequestCache(true).get().getTotal().getRequestCache()
                .getMissCount(), equalTo(0L));
    }
}
