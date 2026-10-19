import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.community.residence.common.notice.mapper.NoticeMapper;
import com.community.residence.common.schedule.mapper.SysTaskLogMapper;
import com.community.residence.common.schedule.service.TaskLogService;
import com.community.residence.common.schedule.task.NoticeAutoOfflineTask;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/** 验收取证入口：使用固定Jar的真实任务和Mapper，在隔离库触发一次到期下线。 */
public class AcceptanceNoticeTask {
    public static void main(String[] args) throws Exception {
        MysqlDataSource source = new MysqlDataSource();
        source.setURL("jdbc:mysql://127.0.0.1:3306/community_residence_dc_20261019?characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        source.setUser(System.getenv().getOrDefault("DB_USERNAME", "root"));
        source.setPassword(System.getenv("DB_PASSWORD"));
        MybatisConfiguration config = new MybatisConfiguration();
        config.setEnvironment(new Environment("acceptance-isolated", new JdbcTransactionFactory(), source));
        config.addMapper(NoticeMapper.class);
        config.addMapper(SysTaskLogMapper.class);
        Config redis = new Config();
        redis.useSingleServer().setAddress("redis://127.0.0.1:6387");
        RedissonClient client = Redisson.create(redis);
        try (SqlSession session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true)) {
            new NoticeAutoOfflineTask(session.getMapper(NoticeMapper.class),
                    new TaskLogService(session.getMapper(SysTaskLogMapper.class)), client).execute();
        } finally {
            client.shutdown();
        }
    }
}
