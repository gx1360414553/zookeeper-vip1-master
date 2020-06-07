package org.apache.zookeeper.version;

/**
 * @author 高雄
 * @version 1.0.0
 * @Description TODO
 * @createTime 2020年06月07日 14:39:00
 */
public interface Info {
    int MAJOR=1;
    int MINOR=0;
    int MICRO=0;
    String QUALIFIER=null;
    int REVISION=-1; //TODO: remove as related to SVN VCS
    String REVISION_HASH="1";
    String BUILD_DATE="2019-3-4";
}
