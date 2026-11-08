<template>
  <el-container class="shell">
    <el-aside :width="collapsed ? '64px' : '220px'" class="side">
      <div class="brand" @click="router.push('/dashboard')">
        <img class="logo" src="/brand.svg" alt="" />
        <div v-if="!collapsed" class="brand-text">
          <div class="name">星络 <span class="en">Xinglo</span></div>
          <div class="sub">管理后台</div>
        </div>
      </div>
      <el-menu :default-active="active" :collapse="collapsed" router class="menu">
        <el-menu-item index="/dashboard"><el-icon><DataLine /></el-icon><template #title>总览</template></el-menu-item>
        <el-menu-item index="/users"><el-icon><User /></el-icon><template #title>用户管理</template></el-menu-item>
        <el-menu-item v-if="isSuper()" index="/admins"><el-icon><Avatar /></el-icon><template #title>管理员</template></el-menu-item>
        <el-menu-item index="/orders"><el-icon><Wallet /></el-icon><template #title>会员与账单</template></el-menu-item>
        <el-menu-item index="/models"><el-icon><Cpu /></el-icon><template #title>模型配置</template></el-menu-item>
        <el-menu-item index="/integrations"><el-icon><Connection /></el-icon><template #title>第三方平台</template></el-menu-item>
        <el-menu-item index="/spot-intros"><el-icon><Location /></el-icon><template #title>景点简介</template></el-menu-item>
        <el-menu-item index="/logs"><el-icon><Document /></el-icon><template #title>操作日志</template></el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="top">
        <el-button text :icon="collapsed ? Expand : Fold" @click="collapsed = !collapsed" />
        <div class="crumb">{{ route.meta.title }}</div>
        <el-dropdown trigger="click" @command="onCommand">
          <div class="me">
            <div class="avatar">{{ initial }}</div>
            <span class="me-name">{{ session.admin?.nickname || session.admin?.username }}</span>
            <el-tag size="small" :type="isSuper() ? 'primary' : 'info'" effect="light">{{ isSuper() ? "超级管理员" : "管理员" }}</el-tag>
            <el-icon><ArrowDown /></el-icon>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="password" :icon="Key">修改密码</el-dropdown-item>
              <el-dropdown-item command="logout" :icon="SwitchButton" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ArrowDown, Avatar, Connection, Cpu, DataLine, Document, Expand, Fold, Key, Location, SwitchButton, User, Wallet } from "@element-plus/icons-vue";
import { isSuper, session, signOut } from "../session";

/** 后台框架：左侧菜单（管理员菜单只有超级管理员看得到）、顶部账号菜单 */
const route = useRoute();
const router = useRouter();
const collapsed = ref(window.innerWidth < 900);
const active = computed(() => "/" + (route.path.split("/")[1] || "dashboard"));
const initial = computed(() => (session.admin?.nickname || session.admin?.username || "?").slice(0, 1).toUpperCase());

async function onCommand(cmd: string) {
  if (cmd === "password") router.push("/password");
  if (cmd === "logout") {
    await signOut();
    router.replace("/login");
  }
}
</script>

<style scoped>
.shell {
  height: 100%;
}
.side {
  background: #fff;
  border-right: 1px solid var(--x-line);
  transition: width 0.2s;
  overflow: hidden;
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 14px;
  cursor: pointer;
}
.logo {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
}
.name {
  font-weight: 800;
  font-size: 16px;
  white-space: nowrap;
}
.en {
  color: var(--el-color-primary);
  font-size: 13px;
}
.sub {
  font-size: 12px;
  color: var(--x-muted);
}
.menu {
  border-right: none;
}
.top {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #fff;
  border-bottom: 1px solid var(--x-line);
  height: 56px;
}
.crumb {
  flex: 1;
  font-weight: 600;
}
.me {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}
.avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--el-color-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
}
.me-name {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.main {
  padding: 20px;
}
@media (max-width: 600px) {
  .me-name,
  .me .el-tag {
    display: none;
  }
  .main {
    padding: 14px;
  }
}
</style>
