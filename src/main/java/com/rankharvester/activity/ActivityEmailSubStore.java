package com.rankharvester.activity;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

/**
 * 「最新活动通知」邮件订阅落地：订阅者只需一个邮箱。检测到<b>新活动</b>时，把活动长图发到所有订阅邮箱。
 *
 * <p>与 {@link com.rankharvester.rank.slice.SliceSubscriptionStore} 同构，复用分区获取专用第二连接。
 */
@Repository
public class ActivityEmailSubStore {

    private static final Logger log = LoggerFactory.getLogger(ActivityEmailSubStore.class);

    private final Connection conn;

    public ActivityEmailSubStore(@Qualifier("duckDbMetaConnection") Connection duckDbMetaConnection) {
        this.conn = duckDbMetaConnection;
    }

    @PostConstruct
    void ensure() {
        synchronized (conn) {
            try (var st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS activity_sub(
                            email      VARCHAR PRIMARY KEY,
                            created_at BIGINT
                        )""");
            } catch (SQLException e) {
                throw new IllegalStateException("建 activity_sub 表失败", e);
            }
        }
        log.info("activity_sub 表就绪（{} 个最新活动订阅）", count());
    }

    /** 新增订阅（幂等）。返回 true=新订阅，false=此前已订阅。 */
    public boolean subscribe(String email) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement(
                    "INSERT INTO activity_sub(email,created_at) VALUES(?,?) ON CONFLICT (email) DO NOTHING")) {
                ps.setString(1, email);
                ps.setLong(2, System.currentTimeMillis());
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new IllegalStateException("写活动订阅失败", e);
            }
        }
    }

    public void unsubscribe(String email) {
        synchronized (conn) {
            try (var ps = conn.prepareStatement("DELETE FROM activity_sub WHERE email=?")) {
                ps.setString(1, email);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("删活动订阅失败", e);
            }
        }
    }

    public int count() {
        synchronized (conn) {
            try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM activity_sub")) {
                return rs.next() ? rs.getInt(1) : 0;
            } catch (SQLException e) {
                return 0;
            }
        }
    }

    /** 所有订阅邮箱（推送时遍历）。 */
    public List<String> allEmails() {
        var out = new ArrayList<String>();
        synchronized (conn) {
            try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT email FROM activity_sub")) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            } catch (SQLException e) {
                log.warn("读活动订阅列表失败: {}", e.getMessage());
            }
        }
        return out;
    }
}
