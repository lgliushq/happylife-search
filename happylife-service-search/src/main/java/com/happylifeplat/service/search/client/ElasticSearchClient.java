package com.happylifeplat.service.search.client;

import com.happylifeplat.facade.search.enums.OrderByEnum;
import com.happylifeplat.service.search.entity.GoodsEs;
import com.happylifeplat.service.search.entity.ProviderRegionEs;
import com.happylifeplat.service.search.helper.LogUtil;
import com.happylifeplat.service.search.query.SearchEntity;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.byscroll.BulkByScrollResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.HasChildQueryBuilder;
import org.elasticsearch.index.query.HasParentQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.TermQueryBuilder;
import org.elasticsearch.index.reindex.DeleteByQueryAction;
import org.elasticsearch.search.sort.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.elasticsearch.index.query.QueryBuilders.*;
import static org.elasticsearch.index.query.QueryBuilders.hasParentQuery;

/**
 * <p>Description: .</p>
 * <p>Company: 深圳市旺生活互联网科技有限公司</p>
 * <p>Copyright: 2015-2017 happylifeplat.com All Rights Reserved</p>
 * es客户端 spring来初始化，作为单列存在
 *
 * @author yu.xiao@happylifeplat.com
 * @version 1.0
 * @date 2017/3/29 17:30
 * @since JDK 1.8
 */
public class ElasticSearchClient {

    private static TransportClient client;

    private static final int DEFAULT_PORT = 9300;//elasticsearch 集群的主节点的默认是9300
    /**
     * logger
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticSearchClient.class);

    private ElasticSearchClient(String clusterName, String ip, Integer port) {
        if (StringUtils.isEmpty(ip)) {
            throw new IllegalArgumentException("elasticsearch cluster master node ip cannot be null.");
        }
        if (null == port || port == 0) {
            port = DEFAULT_PORT;
        }
        //es集群的设置信息
        Settings settings = Settings.builder().put("client.transport.sniff", true)
                .put("cluster.name", clusterName).build();
           /* client = TransportClient.builder()
                    .settings(settings)
                    .build()
                    .addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName(ip), port));*/
        LogUtil.info(LOGGER, () -> "elasticsearch 客户端初始化成功！");
    }


    /**
     * 批量构建商品索引  和服务区域是父子关联关系
     *
     * @param index 索引
     * @param type  类型
     * @param list  商品集合
     * @return true false
     */
    public static boolean bulkGoodsIndex(String index, String type, List<GoodsEs> list) {
        BulkRequestBuilder bulkRequest = client.prepareBulk();
        list.forEach(goodsEs -> bulkRequest.add(
                client.prepareIndex(index, type, goodsEs.getId()).setSource(goodsEs)));
        return !bulkRequest.get().hasFailures();
    }

    public static boolean bulkDelete(String index, String type, List<String> ids) {
        BulkRequestBuilder bulkRequest = client.prepareBulk();
        ids.forEach(id -> bulkRequest.add(client.prepareDelete(index, type, id)));
        return !bulkRequest.get().hasFailures();
    }

    public static void bulkDeleteRegion(String index, List<String> providerIds) {
        providerIds.forEach(providerId -> {
            final BulkByScrollResponse bulkByScrollResponse =
                    DeleteByQueryAction.INSTANCE.newRequestBuilder(client)
                            .filter(matchQuery("providerId", providerId))
                            .source(index)
                            .get();
            bulkByScrollResponse.getDeleted();
        });

    }

    /**
     * 创建服务区域索引  和商品type是父子关联关系
     *
     * @param index 索引
     * @param type  类型
     * @param list  服务区域集合
     * @return true false
     */
    public static boolean bulkRegionIndex(String index, String type, List<ProviderRegionEs> list) {
        BulkRequestBuilder bulkRequest = client.prepareBulk();
        list.forEach(providerRegionEs -> bulkRequest.add(
                client.prepareIndex(index, type)
                        .setParent(providerRegionEs.getGoodsId())
                        .setSource(providerRegionEs)));
        return !bulkRequest.get().hasFailures();
    }


    /**
     * es 父子关系文档联合查询
     *
     * @param index        索引
     * @param parentType   父文档
     * @param childType    子文档
     * @param searchEntity 查询条件
     * @return SearchResponse
     */
    public static SearchResponse multiMatchQueryRelation(String index, String parentType, String childType, SearchEntity searchEntity) {
        LogUtil.info(LOGGER, () -> "index = [" + index + "], " +
                "parentType = [" + parentType + "], childType = [" + childType + "]," +
                " searchEntity = [" + searchEntity + "]");
        SortOrder sortOrder = buildSort(searchEntity);

        QueryBuilder parentQb = QueryBuilders
                .multiMatchQuery(searchEntity.getKeywords(), searchEntity.getFields());
        final TermQueryBuilder childTermQuery =
                QueryBuilders.termQuery(searchEntity.getChildField(), searchEntity.getRegionId());
       // final HasParentQueryBuilder hasParentQueryBuilder = hasParentQuery(parentType, parentQb, Boolean.TRUE);


        //final HasChildQueryBuilder hasChildQueryBuilder = hasChildQuery(childType, childTermQuery, ScoreMode.None);
        final BoolQueryBuilder queryBuilder = boolQuery()
                .must(matchAllQuery())
                .filter(hasParentQuery(parentType,
                        boolQuery().should(parentQb)
                                .filter(hasChildQuery(childType, childTermQuery, ScoreMode.None)),
                        Boolean.TRUE));
        SearchResponse response = client.prepareSearch(index)
                .setTypes(parentType)
                .setQuery(queryBuilder)
                .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                .addSort(searchEntity.getOrderField(), sortOrder)
                .setFrom(searchEntity.getPage())
                .setSize(searchEntity.getSize())
                .setExplain(true)
                .execute()
                .actionGet();
        LogUtil.info(LOGGER, () -> "elastic relation search index: " + index + " parentType:" + parentType + " childType:" + childType + " successful ");
        return response;
    }


    /**
     * 多字段查询
     *
     * @param index        索引
     * @param type         索引类型
     * @param searchEntity 查询实体
     * @return es查询结果
     */
    public static SearchResponse multiMatchQuery(String index, String type, SearchEntity searchEntity) {
        LogUtil.info(LOGGER, () -> " " + "index = [" + index + "], type = [" + type + "], searchEntity = [" + searchEntity + "]");
        if (searchEntity.getFields().length == 0 || searchEntity.getFields() == null) {
            return null;
        }
        SortOrder sortOrder = buildSort(searchEntity);
        QueryBuilder qb = QueryBuilders
                .multiMatchQuery(searchEntity.getKeywords(), searchEntity.getFields());

        SearchResponse response = client.prepareSearch(index)
                .setTypes(type)
                .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                .setQuery(qb)
                .addSort(searchEntity.getOrderField(), sortOrder)
                .setFrom(searchEntity.getPage())
                .setSize(searchEntity.getSize())
                .setExplain(true)
                .execute()
                .actionGet();
        LogUtil.info(LOGGER, () -> "elastic  multiMatchQuery search index: " + index + " type:" + type + " successful ");
        return response;

    }


    /**
     * 多字段查询
     *
     * @param index        索引
     * @param type         索引类型
     * @param searchEntity 查询实体
     * @return es查询结果
     */
    public static SearchResponse multiFieldQuery(String index, String type, SearchEntity searchEntity) {
        LogUtil.info(LOGGER, () -> " " + "index = [" + index + "], type = [" + type + "], searchEntity = [" + searchEntity + "]");

        Map<String, Object> fieldMap = searchEntity.getFieldMap();

        if (fieldMap == null || fieldMap.isEmpty()) {
            return null;
        }
        SortOrder sortOrder = buildSort(searchEntity);

        BoolQueryBuilder bqb = boolQuery();

        fieldMap.forEach((key, value) -> bqb.should(matchQuery(key, value)));

        SearchResponse response = client.prepareSearch(index)
                .setTypes(type)
                .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                .setQuery(bqb)
                .addSort(searchEntity.getOrderField(), sortOrder)
                .setFrom(searchEntity.getPage())
                .setSize(searchEntity.getSize())
                .setExplain(true)
                .execute()
                .actionGet();
        LogUtil.info(LOGGER, () ->
                "elastic search  by multiFieldQuery index: "
                        + index + " type:" + type + " successful ");
        return response;

    }


    /**
     * 关键词查询 单字段
     *
     * @param index        索引
     * @param type         索引类型
     * @param searchEntity 查询实体
     * @return es查询结果
     */
    public static SearchResponse termQuery1(String index, String type, SearchEntity searchEntity) {
        LogUtil.info(LOGGER, () -> " " + "index = [" + index + "], type = [" + type + "], searchEntity = [" + searchEntity + "]");

        if (StringUtils.isEmpty(searchEntity.getField())) {
            return null;
        }
        SortOrder sortOrder = buildSort(searchEntity);

        QueryBuilder queryBuilder = QueryBuilders
                .termQuery(searchEntity.getField(), searchEntity.getKeywords());
        SearchResponse response = client.prepareSearch(index)
                .setTypes(type)
                .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                .setQuery(queryBuilder)
                .addSort(searchEntity.getOrderField(), sortOrder)
                .setFrom(searchEntity.getPage())
                .setSize(searchEntity.getSize())
                .setExplain(true)
                .execute()
                .actionGet();
        LogUtil.info(LOGGER, () ->
                "elastic termQuery  by  index: "
                        + index + " type:" + type + " successful ");
        return response;

    }

    /**
     * 构建排序信息
     *
     * @param searchEntity 查询实体类
     * @return SortOrder
     */
    private static SortOrder buildSort(SearchEntity searchEntity) {
        SortOrder sortOrder;
        if (Objects.equals(searchEntity.getSortOrder(), OrderByEnum.ASC.toString())) {
            sortOrder = SortOrder.ASC;
        } else if (Objects.equals(OrderByEnum.DESC.toString(), searchEntity.getSortOrder())) {
            sortOrder = SortOrder.DESC;
        } else {
            sortOrder = SortOrder.ASC;
        }
        return sortOrder;
    }


    /**
     * 查询所有数据
     *
     * @param index 索引
     * @param type  类型
     * @return SearchResponse
     */
    public static SearchResponse searchByIndexAndType(String index, String type) {
        return client.prepareSearch(index).setTypes(type).get();
    }

    /**
     * 关闭es 客户端
     */
    public static void shutdown() {
        client.close();
    }
}
