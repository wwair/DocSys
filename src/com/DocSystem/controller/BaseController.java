package com.DocSystem.controller;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Properties;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.multipart.MultipartFile;
import org.tmatesoft.svn.core.SVNDirEntry;

import util.DateFormat;
import util.ReadProperties;
import util.ReturnAjax;

import com.DocSystem.common.BaseFunction;
import com.DocSystem.common.CommitAction;
import com.DocSystem.common.CommitAction.CommitType;
import com.DocSystem.common.CommonAction;
import com.DocSystem.common.CommonAction.Action;
import com.DocSystem.common.CommonAction.ActionType;
import com.DocSystem.common.CommonAction.DocType;
import com.DocSystem.common.DocChange;
import com.DocSystem.common.DocChange.DocChangeType;
import com.DocSystem.common.UniqueAction;
import com.DocSystem.entity.ChangedItem;
import com.DocSystem.entity.Doc;
import com.DocSystem.entity.DocAuth;
import com.DocSystem.entity.DocLock;
import com.DocSystem.entity.GroupMember;
import com.DocSystem.entity.LogEntry;
import com.DocSystem.entity.Repos;
import com.DocSystem.entity.ReposAuth;
import com.DocSystem.entity.Role;
import com.DocSystem.entity.SysConfig;
import com.DocSystem.entity.User;
import com.DocSystem.entity.UserGroup;
import com.DocSystem.service.impl.ReposServiceImpl;
import com.DocSystem.service.impl.UserServiceImpl;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mysql.jdbc.Connection;
import com.mysql.jdbc.Statement;

import util.GitUtil.GITUtil;
import util.LuceneUtil.LuceneUtil2;
import util.SvnUtil.SVNUtil;

public class BaseController  extends BaseFunction{
	@Autowired
	protected ReposServiceImpl reposService;
	@Autowired
	protected UserServiceImpl userService;
	
	/****************************** DocSys Doc列表获取接口 **********************************************/
	//getAccessableSubDocList
	protected List<Doc> getAccessableSubDocList(Repos repos, Doc doc, DocAuth docAuth, HashMap<Long, DocAuth> docAuthHashMap, ReturnAjax rt, List<CommonAction> actionList) 
	{	
		System.out.println("getAccessableSubDocList() " + doc.getDocId() + " " + doc.getPath() + doc.getName() );
						
		List<Doc> docList = getAuthedSubDocList(repos, doc, docAuth, docAuthHashMap, rt, actionList);
	
		if(docList != null)
		{
			Collections.sort(docList);
		
			printObject("getAccessableSubDocList() docList:", docList);
		}
		
		addDocToSyncUpList(actionList, repos, doc);
		
		return docList;
	}
	
	protected boolean checkDocLocked(Integer reposId, Doc doc, User login_user, boolean subDocCheckFlag) 
	{	
		//check if the doc was locked (State!=0 && lockTime - curTime > 1 day)
		DocLock docLock = getDocLock(doc);
		ReturnAjax rt = new ReturnAjax();
		if(docLock != null && isDocLocked(docLock,login_user,rt ))
		{
			System.out.println("lockDoc() Doc " + doc.getName() +" was locked");
			return true;
		}
		
		//检查其父节点是否强制锁定
		if(isParentDocLocked(doc,login_user,rt))
		{
			System.out.println("lockDoc() Parent Doc of " + doc.getName() +" was locked！");				
			return true;
		}
		
		//Check If SubDoc was locked
		if(subDocCheckFlag)
		{
			if(isSubDocLocked(doc,login_user, rt) == true)
			{
				System.out.println("lockDoc() subDoc of " + doc.getName() +" was locked！");
				return true;
			}
		}
		
		return false;
	}

	//getSubDocHashMap will do get HashMap for subDocList under pid,
	protected List<Doc> getAuthedSubDocList(Repos repos, Doc doc, DocAuth pDocAuth, HashMap<Long, DocAuth> docAuthHashMap, ReturnAjax rt, List<CommonAction> actionList)
	{
		List<Doc> docList = new ArrayList<Doc>();
		List<Doc> tmpDocList = docSysGetSubDocList(repos, doc);

		if(tmpDocList != null)
    	{
	    	for(int i=0;i<tmpDocList.size();i++)
	    	{
	    		Doc dbDoc = tmpDocList.get(i);
	    		
	    		DocAuth docAuth = getDocAuthFromHashMap(dbDoc.getDocId(), pDocAuth,docAuthHashMap);
				if(docAuth != null && docAuth.getAccess()!=null && docAuth.getAccess() == 1)
				{
		    		//Add to docList
		    		docList.add(dbDoc);
				}
	    	}
    	}
		return docList;
	}

	private List<Doc> getDBEntryList(Repos repos, Doc doc) {
		Doc qDoc = new Doc();
		qDoc.setVid(repos.getId());
		qDoc.setPid(doc.getDocId());
		return reposService.getDocList(qDoc);
	}

	private List<Doc> getLocalEntryList(Repos repos, Doc doc) 
	{
		//System.out.println("getLocalEntryList() " + doc.getDocId() + " " + doc.getPath() + doc.getName());
    	
		String localRootPath = getReposRealPath(repos);
		String localVRootPath = getReposVirtualPath(repos);
		
		String docName = doc.getName();
		if(doc.getDocId() == 0)
		{
			docName = "";
		}

		File dir = new File(localRootPath + doc.getPath() + docName);
    	if(false == dir.exists())
    	{
    		System.out.println("getLocalEntryList() " + doc.getPath() + docName + " 不存在！");
    		return null;
    	}
    	
    	if(dir.isFile())
    	{
    		System.out.println("getLocalEntryList() " + doc.getPath() + docName + " 不是目录！");
    		return null;
    	}

		String subDocParentPath = doc.getPath() + docName + "/";
		if(docName.isEmpty())
		{
			subDocParentPath = doc.getPath();
		}
		
		Integer subDocLevel = getSubDocLevel(doc);
    	
        //Go through the subEntries
    	List <Doc> subEntryList =  new ArrayList<Doc>();
    	
    	File[] localFileList = dir.listFiles();
    	for(int i=0;i<localFileList.length;i++)
    	{
    		File file = localFileList[i];
    		
    		int type = file.isDirectory()? 2:1;
    		String name = file.getName();
    		//System.out.println("getLocalEntryList subFile:" + name);

    		Doc subDoc = buildBasicDoc(repos.getId(), null, doc.getDocId(), subDocParentPath, name, subDocLevel, type, true, localRootPath, localVRootPath, file.length(), "");
    		subDoc.setSize(file.length());
    		subDoc.setLatestEditTime(file.lastModified());
    		subDoc.setCreateTime(file.lastModified());
    		subEntryList.add(subDoc);
    	}
    	return subEntryList;
	}
    	

	protected Integer getSubDocLevel(Doc doc) {
		if(doc.getLevel() == null)
		{
			doc.setLevel(getLevelByParentPath(doc.getPath()));
		}
		
		return doc.getLevel() + 1;
	}

	private Integer getParentDocLevel(Doc doc) {
		if(doc.getLevel() == null)
		{
			doc.setLevel(getLevelByParentPath(doc.getPath()));
		}
		
		return doc.getLevel() - 1;
	}

	private List<Doc> getRemoteEntryList(Repos repos, Doc doc) {
		//System.out.println("getRemoteEntryList() " + doc.getDocId() + " [" + doc.getPath() + doc.getName() + "]");

		switch(repos.getVerCtrl())
		{
		case 1:	//SVN
			SVNUtil svnUtil = new SVNUtil();
			if(false == svnUtil.Init(repos, true, null))
			{
				System.out.println("getRemoteEntryList() svnUtil.Init Failed");
				return null;
			}
			
			//Get list from verRepos
			return svnUtil.getDocList(repos, doc, null); 
		case 2:	//GIT
			
			GITUtil gitUtil = new GITUtil();
			if(false == gitUtil.Init(repos, true, null))
			{
				System.out.println("getRemoteEntryList() gitUtil.Init Failed");
				return null;
			}
			
			//Get list from verRepos
			return gitUtil.getDocList(repos, doc, null); 
		}
		return null;
	}

	protected boolean isDirLocalChanged(Repos repos, Doc doc) 
	{
		HashMap<String, Doc> docHashMap = new HashMap<String, Doc>();	//the doc already scanned
		
		Doc subDoc = null;
		List<Doc> dbDocList = getDBEntryList(repos, doc);
		//printObject("isDirLocalChanged() dbEntryList:", dbDocList);
	   	if(dbDocList != null)
    	{
	    	for(int i=0;i<dbDocList.size();i++)
	    	{
	    		subDoc = dbDocList.get(i);
	    		docHashMap.put(subDoc.getName(), subDoc);
	    		printObject("isDirLocalChanged() dbDoc:", subDoc);
	    	   	
	    		Doc subLocalEntry = fsGetDoc(repos, subDoc);
	    		printObject("isDirLocalChanged() localEntry: ", subLocalEntry);
	    		if(subLocalEntry.getType() == 0)
	    		{
	    			System.out.println("isDirLocalChanged() 本地文件删除: " + subDoc.getDocId() + " " + subDoc.getPath() + subDoc.getName());
	    			return true;
	    		}
	    		
	    		if(!subLocalEntry.getType().equals(subDoc.getType()))
	    		{
	    			System.out.println("isDirLocalChanged() 本地文件类型变化: " + subDoc.getDocId() + " " + subDoc.getPath() + subDoc.getName());
	    			return true;
	    		}
	    		
	    		if(subDoc.getType() == 2)
	    		{
	    			if(isDirLocalChanged(repos, subDoc))
	    			{
	    				return true;
	    			}
	    			continue;
	    		}
	    		
	    		if(isDocLocalChanged(subDoc, subLocalEntry))
	    		{
	    			System.out.println("isDirLocalChanged() 本地文件内容修改: " + subDoc.getDocId() + " " + subDoc.getPath() + subDoc.getName());
	    			return true;
	    		}
	    	}
    	}

    	List<Doc> localEntryList = getLocalEntryList(repos, doc);
		printObject("isDirLocalChanged() localEntryList:", localEntryList);
		if(localEntryList != null)
    	{
	    	for(int i=0;i<localEntryList.size();i++)
	    	{
	    		subDoc = localEntryList.get(i);
	    		if(docHashMap.get(subDoc.getName()) != null)
	    		{
	    			//already scanned
	    			continue;	
	    		}
	    		
	    		//local Added
    			System.out.println("isDirLocalChanged() local Doc Added: " + subDoc.getDocId() + " " + subDoc.getPath() + subDoc.getName());
	    		return true;
	    	}
    	}
		
		return false;
	}

	protected boolean isDocLocalChanged(Doc dbDoc, Doc localEntry) 
	{
		//文件大小变化了则一定是变化了
		if(!dbDoc.getSize().equals(localEntry.getSize()))
		{
			System.out.println("isDocLocalChanged() local changed: dbDoc.size:" + dbDoc.getSize() + " localEntry.size:" + localEntry.getSize()); 
			return true;			
		}
				
		//如果日期和大小都没变表示文件没有改变
		if(!dbDoc.getLatestEditTime().equals(localEntry.getLatestEditTime()))
		{
			
			System.out.println("isDocLocalChanged() local changed: dbDoc.lastEditTime:" + dbDoc.getLatestEditTime() + " localEntry.lastEditTime:" + localEntry.getLatestEditTime()); 
			return true;
		}
		
		//如果日期和大小都没变表示文件没有改变
		if(dbDoc.getRevision() == null || dbDoc.getRevision().isEmpty())
		{
			System.out.println("isDocLocalChanged() local changed: dbDoc.revision is null or empty:" + dbDoc.getRevision()); 
			return true;
		}
		
		return false;
	}
	
	protected boolean isDocRemoteChanged(Repos repos, Doc dbDoc, Doc remoteEntry) 
	{
		if(repos.getVerCtrl() == 0)
		{
			return false;
		}
		
		if(dbDoc.getRevision() != null && !dbDoc.getRevision().isEmpty() && dbDoc.getRevision().equals(remoteEntry.getRevision()))
		{
			return false;
		}
		
		System.out.println("isDocRemoteChanged() remote changed: dbDoc.revision:" + dbDoc.getRevision() + " remoteEntry.revision:" + remoteEntry.getRevision()); 
		//printObject("isDocRemoteChanged() doc:",dbDoc);
		//printObject("isDocRemoteChanged() remoteEntry:",remoteEntry);
		return true;
	}

	private HashMap<String, Doc> getIndexHashMap(Repos repos, Long pid, String path) 
	{
		System.out.println("getIndexHashMap() path:" + path); 
		List<Doc> docList = null;
		Doc doc = new Doc();
		doc.setPath(path);
		doc.setVid(repos.getId());
		docList = reposService.getDocList(doc);
		
		return BuildHashMapByDocList(docList, pid, path);
	}
	
	protected HashMap<String, Doc> BuildHashMapByDocList(List<Doc> docList, Long pid, String path) 
	{
		if(docList == null)
		{
			return null;
		}
		
		HashMap<String,Doc> hashMap = new HashMap<String,Doc>();
    	for(int i=0;i<docList.size();i++)
    	{
			Doc doc = docList.get(i);
			doc.setPid(pid);			
			hashMap.put(doc.getName(), doc);
		}		
		return hashMap;
	}
	
	//
	protected List<Doc> getDocListFromRootToDoc(Repos repos, Doc doc, DocAuth rootDocAuth,  HashMap<Long, DocAuth> docAuthHashMap, ReturnAjax rt, List<CommonAction> actionList)
	{
		System.out.println("getDocListFromRootToDoc() reposId:" + repos.getId() + " parentPath:" + doc.getPath() +" docName:" + doc.getName());
		
		Doc rootDoc = buildBasicDoc(repos.getId(), 0L, -1L, "", "", 0, 2, true, doc.getLocalRootPath(), doc.getLocalVRootPath(), null, null);
		
		List<Doc> resultList = getAccessableSubDocList(repos, rootDoc, rootDocAuth, docAuthHashMap, rt, actionList);	//get subDocList under root
		addDocToSyncUpList(actionList, repos, rootDoc);
		if(resultList == null || resultList.size() == 0)
		{
			System.out.println("getDocListFromRootToDoc() docList under root is empty");			
			return null;
		}
		
		String [] paths = doc.getPath().split("/");
		int deepth = paths.length;
		System.out.println("getDocListFromRootToDoc() deepth:" + deepth); 
		if(deepth < 1)
		{
			return resultList;
		}
		
		Integer reposId = repos.getId();
		Long pid = 0L;
		String  path = "";
		int level = 0;
		DocAuth pDocAuth = rootDocAuth;
		for(int i=0; i<deepth; i++)
		{
			String name = paths[i];
			if(name.isEmpty())
			{
				continue;
			}	
			
			Doc tempDoc = buildBasicDoc(reposId, null, pid, path, name, level, 2, true, doc.getLocalRootPath(), doc.getLocalVRootPath(), null, null);
			DocAuth docAuth = getDocAuthFromHashMap(doc.getDocId(), pDocAuth, docAuthHashMap);
			
			List<Doc> subDocList = getAccessableSubDocList(repos, tempDoc, docAuth, docAuthHashMap, rt, actionList);
			addDocToSyncUpList(actionList, repos, tempDoc);
			if(subDocList == null || subDocList.size() == 0)
			{
				docSysDebugLog("getDocListFromRootToDoc() Failed to get the subDocList under doc: " + path+name, rt);
				break;
			}
			resultList.addAll(subDocList);
			
			path = path + name + "/";
			pid = tempDoc.getPid();
			pDocAuth = docAuth;
			level++;
		}
		
		return resultList;
	}
	
	protected void addDocToSyncUpList(List<CommonAction> actionList, Repos repos, Doc doc) 
	{
		User autoSync = new User();
		autoSync.setId(0);
		autoSync.setName("AutoSync");
		if(false == checkDocLocked(repos.getId(), doc, autoSync, false))
		{
			//insertSyncUpAction(actionList,repos,doc,5,3,2, null);
			insertCommonAction(actionList,repos,doc, null, null, null, ActionType.AUTOSYNCUP, Action.UNDEFINED, DocType.REALDOC, null, autoSync);
		}
	}
	
	protected List<Repos> getAccessableReposList(Integer userId) {
		System.out.println("getAccessableReposList() userId:" + userId);
		
		//取出用户在系统上的所有仓库权限列表
		//将仓库权限列表转换成HashMap,方便快速从列表中取出仓库的用户权限
		HashMap<Integer,ReposAuth> reposAuthHashMap = getUserReposAuthHashMap(userId);
		printObject("reposAuthHashMap:",reposAuthHashMap);
		if(reposAuthHashMap == null || reposAuthHashMap.size() == 0)
		{
			return null;
		}
		
		//get all reposAuthList to pick up the accessable List
		List<Repos> resultList = new ArrayList<Repos>();
		List<Repos> reposList = reposService.getAllReposList();
		for(int i=0;i<reposList.size();i++)
		{
			Repos repos = reposList.get(i);
			printObject("repos",repos);
			ReposAuth reposAuth = reposAuthHashMap.get(repos.getId());
			printObject("reposAuth",reposAuth);
			if(reposAuth != null && reposAuth.getAccess()!=null && reposAuth.getAccess().equals(1))
			{
				resultList.add(repos);
			}
		}
		
		return resultList;
	}
	
	/****************************** 仓库操作接口 ***************************************************/
	//检查path1和path2是否互相包含
	protected boolean isPathConflict(String path1, String path2) 
	{
		if(path1 == null || path1.isEmpty())
		{
			return false;
		}
		if(path2 == null || path2.isEmpty())
		{
			return false;
		}
		
		path1 = dirPathFormat(path1);
		path2 = dirPathFormat(path2);
		if(path1.length() >= path2.length())
		{
			if(path1.indexOf(path2) == 0)
			{
				System.out.print("isPathConflict() :" + path1 + " is under " + path2);
				return true;
			}
		}
		else
		{
			if(path2.indexOf(path1) == 0)
			{
				System.out.print("isPathConflict() :" + path2 + " is under " + path1);
				return true;
			}
		}
		return false;
	}

	protected boolean checkReposInfoForAdd(Repos repos, ReturnAjax rt) {
		//检查传入的参数
		String name = repos.getName();
		if((name == null) || name.isEmpty())
		{
			System.out.println("仓库名不能为空！");
			rt.setError("仓库名不能为空！");			
			return false;
		}

		if(true == isReposPathBeUsed(repos,rt))
		{
			System.out.println("仓库存储目录 " + repos.getPath() + " 已被使用！");
			rt.setError("仓库存储目录 " + repos.getPath() + " 已被使用！");		
			return false;
		}
		
		//文件系统前置，必须有realDocPath
		if(repos.getType() == 2)
		{
			if(repos.getRealDocPath() == null || repos.getRealDocPath().isEmpty())
			{
				rt.setError("文件存储目录不能为空！");						
				return false;
			}
		}
		
		if(true == isReposRealDocPathBeUsed(repos, rt))
		{
			return false;
		}
			
		//svnPath and svnPath1 duplicate check
		String verReposURI = repos.getSvnPath();
		String verReposURI1 = repos.getSvnPath1();
		if(verReposURI != null && verReposURI1 != null)
		{
			if(!verReposURI.isEmpty() && !verReposURI1.isEmpty())
			{
				verReposURI = dirPathFormat(verReposURI);
				verReposURI1 = dirPathFormat(verReposURI1);
				if(isPathConflict(verReposURI,verReposURI))
				{
					rt.setError("不能使用相同的版本仓库链接！");			
					return false;
				}
			}
		}
		
		//RealDoc verRepos Settings check
		if(checkVerReposInfo(repos, null, true, rt) == false)
		{
			return false;
		}

		//VirtualDoc verRepos Settings check
		if(checkVerReposInfo(repos, null, false, rt) == false)
		{
			return false;
		}

		return true;
	}
	
	private boolean checkVerReposInfo(Repos repos,  Repos oldRepos, boolean isRealDoc,ReturnAjax rt) {
		//Check RealDoc VerRepos Settings
		Integer verCtrl = null;
		Integer isRemote = null;
		String localSvnPath = null;
		String svnPath = null;
		String oldSvnPath = null;
		
		if(isRealDoc)
		{
			verCtrl = repos.getVerCtrl();
			isRemote = repos.getIsRemote();
			localSvnPath = repos.getLocalSvnPath();
			svnPath = repos.getSvnPath();
			if(oldRepos != null)
			{
				oldSvnPath = oldRepos.getSvnPath();
			}
		}
		else
		{
			verCtrl = repos.getVerCtrl1();
			isRemote = repos.getIsRemote1();
			localSvnPath = repos.getLocalSvnPath1();
			svnPath = repos.getSvnPath1();	
			if(oldRepos != null)
			{
				oldSvnPath = oldRepos.getSvnPath1();
			}
		}
		
		if(verCtrl != 0 )
		{
			if(isRemote == 0)	//本地版本仓库
			{
				//修正localVerReposPath
				if(localSvnPath == null || localSvnPath.isEmpty())
				{
					if(isRealDoc)
					{
						repos.setLocalSvnPath(getDefaultLocalVerReposPath(repos.getPath()));
					}
					else
					{
						repos.setLocalSvnPath1(getDefaultLocalVerReposPath(repos.getPath()));						
					}
				}			
			}	
			else	//远程版本仓库
			{
				if(svnPath == null || svnPath.isEmpty())
				{
					System.out.println("版本仓库链接不能为空");	//这个其实还不是特别严重，只要重新设置一次即可
					rt.setError("版本仓库链接不能为空！");
					return false;
				}
				
				if(oldSvnPath == null || !svnPath.equals(oldSvnPath))
				{
					//检查版本仓库地址是否已使用
					if(isVerReposPathBeUsed(repos.getId(),svnPath) == true)
					{
						System.out.println("版本仓库地址已使用:" + svnPath);	//这个其实还不是特别严重，只要重新设置一次即可
						rt.setError("版本仓库地址已使用:" + svnPath);
						return false;	
					}
				}
				
				//localVerReposPath setting
				if(verCtrl == 2)
				{
					//修正localVerReposPath
					if(localSvnPath == null || localSvnPath.isEmpty())
					{
						if(isRealDoc)
						{
							repos.setLocalSvnPath(getDefaultLocalVerReposPath(repos.getPath()));
						}
						else
						{
							repos.setLocalSvnPath1(getDefaultLocalVerReposPath(repos.getPath()));							
						}
					}
				}
			}
		}
		return true;
	}

	protected boolean checkReposInfoForUpdate(Repos newReposInfo, Repos previousReposInfo, ReturnAjax rt) {
		//update repos
		if(newReposInfo.getId() == null)
		{
			rt.setError("仓库ID不能为空!");							
			return false;
		}
				
		//rename仓库
		if(newReposInfo.getName() != null)
		{
			if(newReposInfo.getName().isEmpty())
			{
				rt.setError("名字不能为空！");
				return false;
			}
		}
	
		//Change Path
		if(newReposInfo.getPath() != null)
		{
			if(newReposInfo.getPath().isEmpty())
			{
				rt.setError("位置不能为空！");
				return false;
			}
			
			if(true == isReposPathBeUsed(newReposInfo, rt))
			{
				rt.setError("仓库存储目录 " + newReposInfo.getPath() + " 已被使用！");	
				return false;
			}
		}
		
		String realDocPath = newReposInfo.getRealDocPath();
		if(realDocPath != null && !realDocPath.isEmpty())
		{
			realDocPath = dirPathFormat(realDocPath);
			newReposInfo.setRealDocPath(realDocPath);
			if(true == isReposRealDocPathBeUsed(newReposInfo,rt))
			{
				return false;
			}
		}
		else
		{
			//文件系统前置，必须有realDocPath
			if(newReposInfo.getType() == 2)
			{
				if(realDocPath != null && realDocPath.isEmpty())
				{
					rt.setError("文件存储目录不能为空！");						
					return false;
				}
			}
		}
		
		if(isVerReposInfoChanged(newReposInfo, previousReposInfo, true))
		{
			if(checkVerReposInfo(newReposInfo, previousReposInfo, true, rt) == false)
			{
				return false;
			}
		}
		
		if(isVerReposInfoChanged(newReposInfo, previousReposInfo, false))
		{
			if(checkVerReposInfo(newReposInfo,previousReposInfo, false, rt) == false)
			{
				return false;
			}
		}
		return true;
	}
	
	protected boolean isVerReposInfoChanged(Repos newReposInfo, Repos previousReposInfo, boolean isRealDoc) {
		Integer verCtrl = null;
		Integer isRemote = null;
		String localSvnPath = null;
		String svnPath = null;	
		
		Integer preVerCtrl = null;
		Integer preIsRemote = null;
		String preLocalSvnPath = null;
		String preSvnPath = null;	
		
		if(isRealDoc)
		{
			verCtrl = newReposInfo.getVerCtrl();
			isRemote = newReposInfo.getIsRemote();
			localSvnPath = newReposInfo.getLocalSvnPath();
			svnPath = newReposInfo.getSvnPath();	
			
			preVerCtrl = previousReposInfo.getVerCtrl();
			preIsRemote = previousReposInfo.getIsRemote();
			preLocalSvnPath = previousReposInfo.getLocalSvnPath();
			preSvnPath = previousReposInfo.getSvnPath();
		}
		else
		{
			verCtrl = newReposInfo.getVerCtrl1();
			isRemote = newReposInfo.getIsRemote1();
			localSvnPath = newReposInfo.getLocalSvnPath1();
			svnPath = newReposInfo.getSvnPath1();	
			
			preVerCtrl = previousReposInfo.getVerCtrl1();
			preIsRemote = previousReposInfo.getIsRemote1();
			preLocalSvnPath = previousReposInfo.getLocalSvnPath1();
			preSvnPath = previousReposInfo.getSvnPath1();
		}
		
		if(verCtrl != null && verCtrl != preVerCtrl)
		{
			return true;
		}
		
		if(isRemote != null && isRemote != preIsRemote)
		{
			return true;
		}
		
		if(localSvnPath != null && localSvnPath != preLocalSvnPath)
		{
			return true;
		}
		
		if(svnPath != null && svnPath != preSvnPath)
		{
			return true;
		}

		return false;
	}

	protected boolean initVerRepos(Repos repos, boolean isRealDoc, ReturnAjax rt) {
		Integer verCtrl = null;
		Integer isRemote = null;
		if(isRealDoc)
		{
			verCtrl = repos.getVerCtrl();
			isRemote = repos.getIsRemote();
		}
		else
		{
			verCtrl = repos.getVerCtrl1();
			isRemote = repos.getIsRemote1();
		}
		
		if(verCtrl != 0)
		{
			if(isRemote == 0)
			{	
				//Create a localVersionRepos
				if(createLocalVerRepos(repos, isRealDoc, rt) == null)
				{
					System.out.println("版本仓库创建失败");	//这个其实还不是特别严重，只要重新设置一次即可
					rt.setError("版本仓库的创建失败");	
					return false;
				}
			}
			else
			{
				//If VerRepos is Git, We need to do clone the Repository
				if(verCtrl == 2)
				{
					if(deleteClonedRepos(repos, isRealDoc) == false)
					{
						System.out.println("删除版本仓库失败");
						rt.setError("删除版本仓库失败");	
						return false;						
					}
						
					//Clone the Repository
					if(cloneGitRepos(repos, isRealDoc, rt) == null)
					{
						System.out.println("版本仓库Clone失败");	//这个其实还不是特别严重，只要重新设置一次即可
						rt.setError("版本仓库Clone失败");	
						return false;
					}
				}
				
			}	
		}
		return true;
	}

	public boolean deleteClonedRepos(Repos repos, boolean isRealDoc) {
		String clonedReposPath = getLocalVerReposPath(repos, isRealDoc);
		File localRepos = new File(clonedReposPath);
		if(localRepos.exists())
		{
			return delFileOrDir(clonedReposPath);
		}
		return true;
	}

	private String cloneGitRepos(Repos repos, boolean isRealDoc, ReturnAjax rt) {
		GITUtil gitUtil = new GITUtil();
        
        gitUtil.Init(repos, isRealDoc, "");
        return gitUtil.CloneRepos();
	}

	protected void InitReposAuthInfo(Repos repos, User login_user, ReturnAjax rt) {
		//将当前用户加入到仓库的访问权限列表中
		ReposAuth reposAuth = new ReposAuth();
		reposAuth.setReposId(repos.getId());
		reposAuth.setUserId(login_user.getId());
		reposAuth.setType(1); //权限类型：用户权限
		reposAuth.setPriority(10); //将用户的权限优先级为10(group是1-9),anyUser是0
		reposAuth.setIsAdmin(1); //设置为管理员，可以管理仓库，修改描述、设置密码、设置用户访问权限
		reposAuth.setAccess(1);	//0：不可访问  1：可访问
		reposAuth.setEditEn(1);	//可以修改仓库中的文件和目录
		reposAuth.setAddEn(1);		//可以往仓库中增加文件或目录
		reposAuth.setDeleteEn(1);	//可以删除仓库中的文件或目录
		int ret = reposService.addReposAuth(reposAuth);
		System.out.println("addRepos() addReposAuth return:" + ret);
		if(ret == 0)
		{
			docSysDebugLog("addRepos() addReposAuth return:" + ret, rt);
			System.out.println("新增用户仓库权限失败");
		}
				
		//设置当前用户仓库根目录的访问权限
		DocAuth docAuth = new DocAuth();
		docAuth.setReposId(repos.getId());		//仓库：新增仓库id
		docAuth.setUserId(login_user.getId());	//访问用户：当前登录用户	
		docAuth.setDocId((long) 0); 		//目录：根目录
		docAuth.setType(1); 		//权限类型：用户权限
		docAuth.setPriority(10); 	//权限优先级：user是10, group是1-9,anyUser是0
		docAuth.setIsAdmin(1); 		//管理员：可以管理仓库，修改描述、设置密码、设置用户访问权限
		docAuth.setAccess(1);		//访问权限：0：不可访问  1：可访问
		docAuth.setEditEn(1);		//修改权限：可以修改仓库中的文件和目录
		docAuth.setAddEn(1);		//增加权限：可以往仓库中增加文件或目录
		docAuth.setDeleteEn(1);		//删除权限：可以删除仓库中的文件或目录
		docAuth.setHeritable(1);;	//权限继承：0：不可继承  1：可继承
		ret = reposService.addDocAuth(docAuth);
		System.out.println("addRepos() addDocAuth return:" + ret);
		if(ret == 0)
		{
			docSysDebugLog("addRepos() addReposAuth return:" + ret, rt);
			System.out.println("新增用户仓库根目录权限失败");
		}		
	}
	
	private boolean isReposPathBeUsed(Repos newRepos, ReturnAjax rt) {
		Integer reposId = newRepos.getId();
		String path = newRepos.getPath();
		
		List<Repos> reposList = reposService.getAllReposList();
		for(int i=0; i< reposList.size(); i++)
		{
			Repos repos = reposList.get(i);
			if(reposId == null || !reposId.equals(repos.getId()))
			{
				String reposPath = getReposPath(repos);
				if(reposPath != null && !reposPath.isEmpty())
				{
					reposPath = localDirPathFormat(reposPath);
					if(path.indexOf(reposPath) == 0)	//不能把仓库放到其他仓库下面
					{					
						docSysErrorLog(path + " 已被 " + repos.getName() + "  使用", rt); 
						docSysDebugLog("newReposPath duplicated: repos id="+repos.getId() + " name="+ repos.getName() + " reposPath=" + reposPath, rt); 
						return true;
					}
				}
				
				String realDocPath = repos.getRealDocPath();
				if(realDocPath != null && !realDocPath.isEmpty())
				{
					realDocPath = localDirPathFormat(realDocPath);
					if(path.indexOf(realDocPath) == 0)	//不能把仓库放到其他仓库的文件存储目录
					{					
						docSysErrorLog(path + " 已被 " + repos.getName() + "  使用", rt); 
						docSysDebugLog("newRealDocPath duplicated: repos id="+repos.getId() + " name="+ repos.getName() + " realDocPath=" + realDocPath, rt); 
						return true;
					}
				}
			}
		}
		return false;
	}
	
	private boolean isReposRealDocPathBeUsed(Repos newRepos, ReturnAjax rt) {
		
		String newRealDocPath = newRepos.getRealDocPath();
		
		List<Repos> reposList = reposService.getAllReposList();
		for(int i=0; i< reposList.size(); i++)
		{
			Repos repos = reposList.get(i);
			
			//文件存储路径不得使用仓库的存储路径(避免对仓库的存储目录或者仓库的结构造成破坏)
			String reposPath = repos.getPath();
			if(reposPath != null && !reposPath.isEmpty())
			{
				reposPath = localDirPathFormat(reposPath);
				if(isPathConflict(reposPath,newRealDocPath))
				{					
					docSysErrorLog("文件存储目录：" + newRealDocPath + "已被  " + repos.getName() + " 使用", rt); 
					docSysDebugLog("newRealDocPath duplicated: repos id="+repos.getId() + " name="+ repos.getName() + " reposPath=" + reposPath,rt); 
					return true;
				}
			}
			
			//不同仓库可以使用相同的文件存储路径(不同仓库可以对相同的目录进行不同的管理方式)
//			//检查是否与其他的仓库realDocPath冲突
//			Integer reposId = newRepos.getId();
//			if(reposId == null || repos.getId() != reposId)	//用来区分是否是当前仓库
//			{
//				String realDocPath = repos.getRealDocPath();
//				if(realDocPath != null && !realDocPath.isEmpty())
//				{
//					realDocPath = localDirPathFormat(realDocPath);
//					if(isPathConflict(realDocPath,newRealDocPath))
//					{					
//						docSysErrorLog("文件存储目录：" + newRealDocPath + "已被  " + repos.getName() + " 使用", rt); 
//						docSysDebugLog("newRealDocPath duplicated: repos id="+repos.getId() + " name="+ repos.getName() + " realDocPath=" + realDocPath, rt); 
//						return true;
//					}
//				}
//			}		
		}
		return false;
	}

	private boolean isVerReposPathBeUsed(Integer reposId, String newVerReposPath) {
		
		List<Repos> reposList = reposService.getAllReposList();
				
		for(int i=0; i< reposList.size(); i++)
		{
			Repos repos = reposList.get(i);
			if(repos.getId() == reposId)
			{
				continue;
			}
			
//			//检查远程版本仓库是否已被使用
//			String verReposURI = repos.getSvnPath();
//			if(verReposURI != null && !verReposURI.isEmpty())
//			{
//				if(isPathConflict(verReposURI,newVerReposPath))
//				{					
//					System.out.println("该版本仓库连接已被使用:" + newVerReposPath); 
//					System.out.println("newVerReposPath duplicated: repos id="+repos.getId() + " name="+ repos.getName() + " verReposPath=" + verReposURI); 
//					return true;
//				}
//			}
//			
//			String verReposURI1 = repos.getSvnPath1();
//			if(verReposURI1 != null && !verReposURI1.isEmpty())
//			{
//				if(isPathConflict(verReposURI1,newVerReposPath))
//				{					
//					System.out.println("该版本仓库连接已被使用:" + newVerReposPath); 
//					System.out.println("newVerReposPath duplicated: repos id="+repos.getId() + " name="+ repos.getName() + " verReposPath1=" + verReposURI1); 
//					return true;
//				}
//			}
			
			//检查是否与本地仓库使用了相同的URI
			String localVerReposURI = getLocalVerReposURI(repos,true);
			if(localVerReposURI != null && !localVerReposURI.isEmpty())
			{
				if(isPathConflict(localVerReposURI,newVerReposPath))
				{					
					System.out.println("该版本仓库连接已被使用:" + newVerReposPath); 
					System.out.println("newVerReposPath duplicated: repos id="+repos.getId() + " name="+ repos.getName() + " localVerReposPath=" + localVerReposURI); 
					return true;
				}
			}
			
			String localVerReposURI1 = getLocalVerReposURI(repos,false);
			if(localVerReposURI1 != null && !localVerReposURI1.isEmpty())
			{
				if(isPathConflict(localVerReposURI1,newVerReposPath))
				{					
					System.out.println("该版本仓库连接已被使用:" + newVerReposPath); 
					System.out.println("newVerReposPath duplicated: repos id="+repos.getId() + " name="+ repos.getName() + " localVerReposURI1=" + localVerReposURI1); 
					return true;
				}
			}
			
		}
		return false;
	}

	protected boolean createReposLocalDir(Repos repos, ReturnAjax rt) {
		String path = repos.getPath();		
		File reposRootDir = new File(path);
		if(reposRootDir.exists() == false)
		{
			System.out.println("addRepos() path:" + path + " not exists, do create it!");
			if(reposRootDir.mkdirs() == false)
			{
				rt.setError("创建仓库目录失败:" + path);
				return false;	
			}
		}
		
		String reposDir = getReposPath(repos);
		if(createDir(reposDir) == true)
		{
			if(createDir(reposDir+"data/") == false)
			{
				rt.setError("创建data目录失败");
				return false;
			}
			else
			{
				if(createDir(reposDir+"data/rdata/") == false)
				{
					rt.setError("创建rdata目录失败");
					return false;
				}
				if(createDir(reposDir+"data/vdata/") == false)
				{
					rt.setError("创建vdata目录失败");
					return false;
				}
			}
			
			if(createDir(reposDir+"refData/") == false)
			{
				rt.setError("创建refData目录失败");
				return false;
			}
			else
			{
				if(createDir(reposDir+"refData/rdata/") == false)
				{
					rt.setError("创建refData/rdata目录失败");
					return false;
				}
				if(createDir(reposDir+"refData/vdata/") == false)
				{
					rt.setError("创建refData/vdata目录失败");
					return false;
				}
			}
			
			if(createDir(reposDir+"tmp/") == false)
			{
				rt.setError("创建tmp目录失败");
				return false;
			}
		}	
		else
		{
			rt.setError("创建仓库目录失败："+reposDir);
			return false;
		}
		
		String reposRealDocDir = repos.getRealDocPath();
		if(reposRealDocDir != null && !reposRealDocDir.isEmpty())
		{
			if(createDir(reposRealDocDir) == false)
			{
				rt.setError("创建文件存储目录失败："+reposRealDocDir);
				return false;
			}
		}
		
		return true;
	}

	protected boolean deleteRepos(Repos repos) {
		//Delete Repos in DB
		reposService.deleteRepos(repos.getId());
		
		//Delete Repos LocalDir
		deleteReposLocalDir(repos);
		
		//Delete Repos LocalVerRepos
		deleteLocalVerRepos(repos,true);
		deleteLocalVerRepos(repos,false);

		//Delete IndexLib
    	deleteIndexLib(repos,0);
		deleteIndexLib(repos,1);
    	deleteIndexLib(repos,2);
		
		return true;
	}

	protected void deleteReposLocalDir(Repos repos) {
		String reposDir = getReposPath(repos);
		delDir(reposDir);
	}

	protected void deleteLocalVerRepos(Repos repos, boolean isRealDoc) {
		//Delete LocalVerRepos
		Integer verCtrl = null;
		Integer isRemote = null;
		String localVerReposPath = null;

		if(isRealDoc)
		{
			verCtrl = repos.getVerCtrl();
			isRemote = repos.getIsRemote();
			localVerReposPath = repos.getLocalSvnPath();
		}
		else
		{
			verCtrl = repos.getVerCtrl1();
			isRemote = repos.getIsRemote1();
			localVerReposPath = repos.getLocalSvnPath1();			
		}
		
		if(verCtrl == null || isRemote == null || isRemote != 0 || localVerReposPath == null || localVerReposPath.isEmpty())
		{
			return;
		}
		
		if(verCtrl != 0 && isRemote == 0)
		{
			String localVerReposDir = localVerReposPath + getVerReposName(repos,isRealDoc);
			delDir(localVerReposDir);
		}
		
	}
	
	private String createLocalVerRepos(Repos repos, boolean isRealDoc, ReturnAjax rt) {
		System.out.println("createLocalVerRepos isRealDoc:"+isRealDoc);	
		Integer verCtrl = 0;
		if(isRealDoc)
		{
			verCtrl = repos.getVerCtrl();
		}
		else
		{
			verCtrl = repos.getVerCtrl1();
		}
		
		if(verCtrl == 1)
		{
			return createSvnLocalRepos(repos,isRealDoc, rt);
		}
		else if(verCtrl == 2)
		{
			return createGitLocalRepos(repos, isRealDoc, rt);
		}
		return null;
	}
	
	public String createGitLocalRepos(Repos repos, boolean isRealDoc, ReturnAjax rt) {
		System.out.println("createGitLocalRepos isRealDoc:"+isRealDoc);	

		String localVerRepos = getLocalVerReposPath(repos, isRealDoc);
		File dir = new File(localVerRepos);
		if(dir.exists())
		{
			docSysDebugLog("GIT仓库:"+localVerRepos + "已存在，已直接设置！", rt);
			return localVerRepos;
		}
		
		GITUtil gitUtil = new GITUtil();
		gitUtil.Init(repos, isRealDoc, "");
		String gitPath = gitUtil.CreateRepos();
		return gitPath;
	}

	protected String createSvnLocalRepos(Repos repos, boolean isRealDoc, ReturnAjax rt) {
		System.out.println("createSvnLocalRepos isRealDoc:"+isRealDoc);	
		
		String path = repos.getPath();
		String localPath = null;
		if(isRealDoc)
		{
			localPath = repos.getLocalSvnPath();
		}
		else
		{
			localPath = repos.getLocalSvnPath1();
		}
		
		
		//If use localVerRepos, empty path mean use the the directory: path+/DocSysSvnReposes
		if((localPath == null) || localPath.equals(""))
		{
			localPath = getDefaultLocalVerReposPath(path);
		}
	
		String reposName = getVerReposName(repos,isRealDoc);
		
		File dir = new File(localPath,reposName);
		if(dir.exists())
		{
			docSysDebugLog("SVN仓库:"+localPath+reposName + "已存在，已直接设置！", rt);
			return "file:///" + localPath + reposName;
		}
		
		String svnPath = SVNUtil.CreateRepos(reposName,localPath);
		return svnPath;
	}
	
	protected boolean ChangeReposRealDocPath(Repos newReposInfo, Repos reposInfo, User login_user, ReturnAjax rt) {
		String path = getReposRealPath(newReposInfo);
		String oldPath = getReposRealPath(reposInfo);
		if(!path.equals(oldPath))
		{
			if(path.isEmpty())
			{
				path = getReposRealPath(newReposInfo);
			}
			System.out.println("ChangeReposRealDocPath oldPath:" + oldPath + " newPath:" + path);
			
			if(login_user.getType() != 2)
			{
				System.out.println("普通用户无权修改仓库存储位置，请联系管理员！");
				rt.setError("普通用户无权修改仓库存储位置，请联系管理员！");
				return false;							
			}
			
			//如果目标目录已存在则不复制
			File newDir = new File(path);
			if(!newDir.exists())
			{
				if(copyFileOrDir(oldPath, path,true) == false)
				{
					System.out.println("文件目录迁移失败！");
					rt.setError("修改仓库文件目录失败！");					
					return false;
				}
			}
		}
		return true;
	}

	protected boolean ChangeReposPath(Repos newReposInfo, Repos previousReposInfo, User login_user,ReturnAjax rt) {
		String path = newReposInfo.getPath();
		String oldPath = previousReposInfo.getPath();
		if(path != null && !path.equals(oldPath))
		{
			System.out.println("ChangeReposPath oldPath:" + oldPath + " newPath:" + path);
			
			if(login_user.getType() != 2)
			{
				System.out.println("普通用户无权修改仓库存储位置，请联系管理员！");
				rt.setError("普通用户无权修改仓库存储位置，请联系管理员！");
				return false;							
			}
			
			//newReposRootDir	
			File newReposRootDir = new File(path);
			if(newReposRootDir.exists() == false)
			{
				System.out.println("ChangeReposPath() path:" + path + " not exists, do create it!");
				if(newReposRootDir.mkdirs() == false)
				{
					rt.setError("创建reposRootDir目录失败:" + path);
					return false;	
				}
			}
			
			if(!path.equals(oldPath))
			{
				//Do move the repos
				String reposName = previousReposInfo.getId()+"";
				if(previousReposInfo.getType() == 2)
				{
					reposName = "";
				}
				else
				{
					if(path.indexOf(oldPath) == 0)
					{
						System.out.println("禁止将仓库目录迁移到仓库的子目录中！");
						rt.setError("修改仓库位置失败：禁止迁移到本仓库的子目录");	
						return false;
					}
				}
	
				if(copyFileOrDir(oldPath+reposName, path+reposName,true) == false)
				{
					System.out.println("仓库目录迁移失败！");
					rt.setError("修改仓库位置失败！");					
					return false;
				}
				else
				{
					delFileOrDir(oldPath+reposName);
				}
			}
		}
		return true;
	}	
	
	/******************************* 文件下载接口 *********************************************/
	protected void sendDataToWebPage(String file_name, byte[] data, HttpServletResponse response, HttpServletRequest request)  throws Exception{ 
		//解决中文编码问题: https://blog.csdn.net/u012117531/article/details/54808960
		String userAgent = request.getHeader("User-Agent").toUpperCase();
		if(userAgent.indexOf("MSIE")>0 || userAgent.indexOf("LIKE GECKO")>0)	//LIKE GECKO is for IE10
		{  
			file_name = URLEncoder.encode(file_name, "UTF-8");  
		}else{  
			file_name = new String(file_name.getBytes("UTF-8"),"ISO8859-1");  
		}  
		System.out.println("doGet file_name:" + file_name);
		//解决空格问题
		response.setHeader("content-disposition", "attachment;filename=\"" + file_name +"\"");
		
		try {
			//创建输出流
			OutputStream out = response.getOutputStream();
			out.write(data, 0, data.length);		
			//关闭输出流
			out.close();	
		}catch (Exception e) {
			e.printStackTrace();
			System.out.println("sendDataToWebPage() Exception");
		}
	}
	
	protected int getLocalEntryType(String localParentPath, String entryName) {
		
		File entry = new File(localParentPath,entryName);
		if(!entry.exists())
		{
			System.out.println("getLocalEntryType() Failed: " + localParentPath + entryName + " 不存在 ！");
			return -1;
		}	
		
		if(entry.isFile())
		{
			return 1;
		}
		else if(entry.isDirectory())
		{
			return 2;
		}

		System.out.println("getLocalEntryType() Failed: 未知文件类型！");
		return -1;
	}
	
	protected void sendTargetToWebPage(String localParentPath, String targetName, String tmpDir, ReturnAjax rt,HttpServletResponse response, HttpServletRequest request, boolean deleteEnable) throws Exception 
	{
		File localEntry = new File(localParentPath,targetName);
		if(false == localEntry.exists())
		{
			docSysErrorLog("文件 " + localParentPath + targetName + " 不存在！", rt);
			writeJson(rt, response);
			return;
		}

		//For dir 
		if(localEntry.isDirectory()) //目录
		{
			//doCompressDir and save the zip File under userTmpDir
			String zipFileName = targetName + ".zip";
			if(doCompressDir(localParentPath, targetName, tmpDir, zipFileName, rt) == false)
			{
				docSysErrorLog("压缩目录失败：" + localParentPath + targetName, rt);
				writeJson(rt, response);
				return;
			}
			
			sendFileToWebPage(tmpDir,zipFileName,rt,response, request); 
			
			//Delete zip file
			delFile(tmpDir+zipFileName);
		}
		else	//for File
		{
			//Send the file to webPage
			sendFileToWebPage(localParentPath,targetName,rt, response, request); 			
		}
		
		if(deleteEnable)
		{
			//Delete target file or dir
			delFileOrDir(localParentPath+targetName);
		}
	}
	
	protected void sendFileToWebPage(String localParentPath, String file_name,  ReturnAjax rt,HttpServletResponse response,HttpServletRequest request) throws Exception{
		
		String dstPath = localParentPath + file_name;

		//检查文件是否存在
		File file = new File(dstPath);
		if(!file.exists())
		{	
			docSysErrorLog("文件  "+ dstPath + " 不存在！", rt);
			writeJson(rt, response);
			return;
		}
		
		System.out.println("sendFileToWebPage() file_name befor convert:" + file_name);
		
		//解决中文编码问题
		String userAgent = request.getHeader("User-Agent").toUpperCase();
		if(userAgent.indexOf("MSIE")>0 || userAgent.indexOf("LIKE GECKO")>0)	//LIKE GECKO is for IE10
		{  
			file_name = URLEncoder.encode(file_name, "UTF-8");  
			System.out.println("sendFileToWebPage() file_name after URL Encode:" + file_name);
		}else{  
			file_name = new String(file_name.getBytes("UTF-8"),"ISO8859-1");  
			
			
			System.out.println("sendFileToWebPage() file_name after convert to ISO8859-1:" + file_name);
		}
		//解决空格问题（空格变加号和兼容性问题）
		file_name = file_name.replaceAll("\\+", "%20").replaceAll("%28", "\\(").replaceAll("%29", "\\)").replaceAll("%3B", ";").replaceAll("%40", "@").replaceAll("%23", "\\#").replaceAll("%26", "\\&");
		System.out.println("sendFileToWebPage() file_name:" + file_name);
		
		response.setHeader("content-disposition", "attachment;filename=\"" + file_name +"\"");

		//读取要下载的文件，保存到文件输入流
		FileInputStream in = null;
		//创建输出流
		OutputStream out = null;
		try {
			//读取要下载的文件，保存到文件输入流
			in = new FileInputStream(dstPath);
			//创建输出流
			out = response.getOutputStream();
			//创建缓冲区
			byte buffer[] = new byte[1024];
			int len = 0;
			//循环将输入流中的内容读取到缓冲区当中
			while((len=in.read(buffer))>0){
				//输出缓冲区的内容到浏览器，实现文件下载
				out.write(buffer, 0, len);
			}
			
			in.close();
			in = null;
			out.close();
			out = null;
		}catch (Exception e) {
			if(in != null)
			{
				in.close();
			}
			if(out != null)
			{
				out.close();						
			}
			e.printStackTrace();
			System.out.println("sendFileToWebPage() Exception");
		}
	}

	protected boolean doCompressDir(String srcParentPath, String dirName, String dstParentPath, String zipFileName,ReturnAjax rt) {
		
		//if dstDir not exists create it
		File dstDir = new File(dstParentPath);
		if(!dstDir.exists())
		{
			if(createDir(dstParentPath) == false)
			{
				docSysDebugLog("doCompressDir() Failed to create:" + dstParentPath, rt);
				return false;
			}
		}
		//开始压缩
		if(compressExe(srcParentPath + dirName,dstParentPath + zipFileName) == true)
		{
			System.out.println("压缩完成！");	
		}
		else
		{
			System.out.println("doCompressDir()  压缩失败！");
			docSysDebugLog("压缩  " + srcParentPath + dirName + "to" + dstParentPath + zipFileName  +" 失败", rt);
			return false;
		}
		
		return true;
	}
		
	/***************************Basic Functions For Driver Level  **************************/
	public User getLoginUser(HttpSession session, HttpServletRequest request, HttpServletResponse response, ReturnAjax rt)
	{
		User user = (User) session.getAttribute("login_user");
		if(user == null)
		{
			//尝试自动登录
			Cookie c1 = getCookieByName(request, "dsuser");
			Cookie c2 = getCookieByName(request, "dstoken");
			if(c1!=null&&c2!=null&&c1.getValue()!=null&&c2.getValue()!=null&&!"".equals(c1.getValue())&&!"".equals(c2.getValue()))
			{
				System.out.println("自动登录");
				String userName = c1.getValue();
				String pwd = c2.getValue();
				//tmp_user is used for store the query condition
				User tmp_user = new User();
				tmp_user.setName(userName);			
				tmp_user.setPwd(pwd);
				List<User> uLists = getUserList(userName,pwd);
				boolean ret =loginCheck(rt, tmp_user, uLists, session,response);
				if(ret == false)
				{
					System.out.println("自动登录失败");
					rt.setMsgData("自动登陆失败");
					writeJson(rt, response);
					return null;
				}
				
				System.out.println("自动登录成功");
				//Set session
				session.setAttribute("login_user", uLists.get(0));
				//延长cookie的有效期
				addCookie(response, "dsuser", userName, 7*24*60*60);//一周内免登录
				addCookie(response, "dstoken", pwd, 7*24*60*60);
				System.out.println("用户cookie保存成功");
				System.out.println("SESSION ID:" + session.getId());

				rt.setData(uLists.get(0));	//将数据库取出的用户信息返回至前台
				writeJson(rt, response);
				return null;
			}
			else
			{
				rt.setError("用户未登录");
				writeJson(rt, response);
				return null;
			}
		}
		return user;
	}
	
	/**
	 * 用户数据校验
	 * @param uLists 根据条件从数据库中查出的user列表
	 * @param rt 返回ajax信息的类
	 * @param session 
	 * @param localUser 前台传回的user信息，或者cookies中保存的用户信息
	 * @return
	 */
	public boolean loginCheck(ReturnAjax rt,User localUser, List<User> uLists,HttpSession session,HttpServletResponse response)
	{	
		if(uLists == null)
		{
			System.out.println("loginCheck() uLists is null");
			rt.setError("用户名或密码错误！");
			return false;	
		}
		else if(uLists.size()<1){
			System.out.println("loginCheck() uLists size < 1");
			rt.setError("用户名或密码错误！");
			return false;
		}else if(uLists.size()>1){
			//TODO系统异常需要处理
			System.out.println("loginCheck() uLists size > 1");
			rt.setError("登录失败！");
			return false;
		}
		
		return true;
	}
	
	public List<User> getUserList(String userName,String pwd) {
		User tmp_user = new User();
		//检查用户名是否为空
		if(userName==null||"".equals(userName))
		{
			return null;
		}
		
		tmp_user.setName(userName);
		tmp_user.setPwd(pwd);
		List<User> uList = userService.queryUserExt(tmp_user);
		if(uList == null || uList.size() == 0)
		{
			return null;
		}
		return uList;
	}
	
	public boolean isUserRegistered(String name)
	{
		List <User> uList = getUserList(name,null);
		if(uList == null || uList.size() == 0)
		{
			return false;
		}
		
		return true;
	}
	
	/********************************** Functions For Application Layer 
	 * @param downloadList ****************************************/
	protected String revertDocHistory(Repos repos, Doc doc, String commitId, String commitMsg, String commitUser, User login_user, ReturnAjax rt, HashMap<String, String> downloadList) 
	{			
		if(commitMsg == null)
		{
			commitMsg = doc.getPath() + doc.getName() + " 回退至版本:" + commitId;
		}

		//Checkout to localParentPath
		String localRootPath = doc.getLocalRootPath();
		String localParentPath = localRootPath + doc.getPath();
		
		//Do checkout the entry to
		List<Doc> successDocList = verReposCheckOut(repos, false, doc, localParentPath, doc.getName(), commitId, true, true, downloadList);
		if(successDocList == null || successDocList.size() == 0)
		{
			docSysErrorLog("未找到需要恢复的文件！",rt);
			return null;
		}
		
		printObject("revertDocHistory checkOut successDocList:", successDocList);
		
		//Do commit to verRepos		
		String revision = verReposDocCommit(repos, false, doc, commitMsg, commitUser, rt, true, null, 2, null);
		if(revision == null)
		{			
			docSysDebugLog("revertDocHistory()  verReposAutoCommit 失败", rt);
			return null;
		}
		//推送至远程仓库
		verReposPullPush(repos, doc.getIsRealDoc(), rt);
		
		if(doc.getIsRealDoc())
		{
			//Force update docInfo
			//printObject("revertDocHistory() successDocList:", successDocList);
			for(int i=0; i< successDocList.size(); i++)
			{
				Doc successDoc = successDocList.get(i);
				System.out.println("revertDocHistory() " + successDoc.getDocId() + " [" + doc.getPath() + doc.getName() + "] 恢复成功");
					
				successDoc.setRevision(revision);
				successDoc.setCreator(login_user.getId());
				successDoc.setLatestEditor(login_user.getId());
				dbUpdateDoc(repos, successDoc, true);
				dbCheckAddUpdateParentDoc(repos, successDoc, null);
			}
		}
		return revision;
	}
	
	//底层addDoc接口
	protected boolean addDoc(Repos repos, Doc doc, 
			MultipartFile uploadFile, //For upload
			Integer chunkNum, Integer chunkSize, String chunkParentPath, //For chunked upload combination
			String commitMsg,String commitUser,User login_user, ReturnAjax rt, List<CommonAction> actionList) 
	{
		System.out.println("addDoc() docId:" + doc.getDocId() + " pid:" + doc.getPid() + " parentPath:" + doc.getPath() + " docName:" + doc.getName());
	
		switch(repos.getType())
		{
		case 1:
		case 2:
		case 3:
		case 4:
			return addDoc_FSM(repos, doc,	//Add a empty file
					uploadFile, //For upload
					chunkNum, chunkSize, chunkParentPath, //For chunked upload combination
					commitMsg, commitUser, login_user, rt, actionList);
			
		}
		return false;
	}

	protected boolean addDoc_FSM(Repos repos, Doc doc,	//Add a empty file
			MultipartFile uploadFile, //For upload
			Integer chunkNum, Integer chunkSize, String chunkParentPath, //For chunked upload combination
			String commitMsg,String commitUser,User login_user, ReturnAjax rt, List<CommonAction> actionList) 
	{
		System.out.println("addDoc_FSM()  docId:" + doc.getDocId() + " pid:" + doc.getPid() + " parentPath:" + doc.getPath() + " docName:" + doc.getName() + " type:" + doc.getType());
		
		//add doc detail info
		doc.setCreator(login_user.getId());
		doc.setCreatorName(login_user.getName());
		doc.setLatestEditor(login_user.getId());
		doc.setLatestEditorName(login_user.getName());
		
		DocLock docLock = null;
		synchronized(syncLock)
		{
			//LockDoc
			docLock = lockDoc(doc, 2,  2*60*60*1000, login_user, rt, false);
			if(docLock == null)
			{
				unlock(); //线程锁
				System.out.println("addDoc() lockDoc " + doc.getName() + " Failed!");
				return false;
			}
		}
		
		String localParentPath =  doc.getLocalRootPath() + doc.getPath();
		String localDocPath = localParentPath + doc.getName();
		File localEntry = new File(localDocPath);
		if(localEntry.exists())
		{	
			unlockDoc(doc, login_user, docLock);
			docSysDebugLog("addDoc() " +localDocPath + "　已存在！", rt);
			return false;
		}
		
		if(uploadFile == null)
		{	
			//File must not exists
			if(createRealDoc(repos, doc, rt) == false)
			{	
				unlockDoc(doc, login_user, docLock);
				
				String MsgInfo = "createRealDoc " + doc.getName() +" Failed";
				rt.setError(MsgInfo);
				System.out.println("createRealDoc Failed");
				return false;
			}
		}
		else
		{
			if(updateRealDoc(repos, doc, uploadFile,chunkNum,chunkSize,chunkParentPath,rt) == false)
			{	
				unlockDoc(doc, login_user, null);
				
				String MsgInfo = "updateRealDoc " + doc.getName() +" Failed";
				rt.setError(MsgInfo);
				System.out.println("updateRealDoc Failed");
				return false;
			}
		}
		
		//Update the latestEditTime
		Doc fsDoc = fsGetDoc(repos, doc);
		doc.setCreateTime(fsDoc.getLatestEditTime());
		doc.setLatestEditTime(fsDoc.getLatestEditTime());
		
		String revision = verReposDocCommit(repos, false, doc,commitMsg,commitUser,rt, false, null, 2, null);
		if(revision == null)
		{
			docSysWarningLog("verReposDocCommit Failed", rt);
		}
		else
		{
			//only do dbAddDoc when commit success, otherwise the added doc will not be commit when do syncup (because dbDoc is same to localDoc) 
			doc.setRevision(revision);
			if(dbAddDoc(repos, doc, false, false) == false)
			{	
				docSysWarningLog("Add Node: " + doc.getName() +" Failed！", rt);
			}
			
			//Insert Push Action
			insertCommonAction(actionList, repos, doc, null, commitMsg, commitUser, ActionType.VERREPOS, Action.PUSH, DocType.REALDOC, null, login_user);
		}
		
		//检查dbParentDoc是否已添加
		List <Doc> addedParentDocList = new ArrayList<Doc>();
		dbCheckAddUpdateParentDoc(repos, doc, addedParentDocList);
		if(addedParentDocList.size() > 0)
		{
			rt.setDataEx(addedParentDocList);
		}
				
		//BuildMultiActionListForDocAdd();
		BuildMultiActionListForDocAdd(actionList, repos, doc, commitMsg, commitUser);
		
		if(unlockDoc(doc,login_user,null) == false)
		{
			docSysWarningLog("unlockDoc Failed", rt);
		}
		
		rt.setData(doc);
		rt.setMsgData("isNewNode");
		docSysDebugLog("新增成功", rt); 
		
		return true;
	}

	
	private boolean dbUpdateDocRevision(Repos repos, Doc doc, String revision) {
		System.out.println("dbUpdateDocRevision " + revision + " doc " + doc.getDocId() + " [" +doc.getPath() + doc.getName());

		Doc dbDoc = dbGetDoc(repos, doc, false);
		if(dbDoc == null)
		{
			System.out.println("dbUpdateDocRevision dbDoc " + doc.getDocId() + " [" +doc.getPath() + doc.getName() + "] 不存在");
			doc.setRevision(revision);
			return dbAddDoc(repos,doc, false, false);
		}
		
		if(dbDoc.getRevision() == null || !dbDoc.getRevision().equals(revision))
		{
			dbDoc.setRevision(revision);
			if(dbUpdateDoc(repos, dbDoc, false) == false)
			{
				System.out.println("dbUpdateDocRevision 更新节点版本号失败: " + doc.getDocId() + " [" +doc.getPath() + doc.getName() + "]");	
				return false;
			}
			return true;
		}
		
		return true;
	}
	
	//该接口用于更新父节点的信息: 仓库有commit成功的操作时必须调用
	private void dbCheckAddUpdateParentDoc(Repos repos, Doc doc, List<Doc> parentDocList) 
	{
		if(repos.getType() != 1)
		{
			//For Non FSM type repos, dbNode is not need
			return;
		}
		
		System.out.println("checkAddUpdateParentDoc " + doc.getDocId() + " " +doc.getPath() + doc.getName());
		
		if(doc.getDocId() == 0)
		{
			return;
		}
		
		System.out.println("checkAddUpdateParentDoc pid:" + doc.getPid());
		
		Doc parentDoc = buildBasicDoc(doc.getVid(), doc.getPid(), null, doc.getPath(), "", null, 2, true, doc.getLocalRootPath(), doc.getLocalVRootPath(), 0L, "");
		parentDoc.setRevision(doc.getRevision());

		printObject("checkAddUpdateParentDoc parentDoc:", parentDoc);
		
		Doc dbParentDoc = dbGetDoc(repos, parentDoc, false);
		if(dbParentDoc == null)
		{
			if(parentDocList == null)
			{
				parentDocList = new ArrayList<Doc>();
			}

			if(dbAddDoc(repos, parentDoc, false, false) == true)
			{
				System.out.println("checkAddUpdateParentDoc 新增目录: " + parentDoc.getDocId() + " " + parentDoc.getPath() + parentDoc.getName());

				parentDocList.add(0,parentDoc);	//always add to the top
				dbCheckAddUpdateParentDoc(repos, parentDoc, parentDocList);
			}
		}
		else
		{
			if(dbParentDoc.getRevision() == null || !dbParentDoc.getRevision().equals(doc.getRevision()))
			{
				parentDoc.setId(dbParentDoc.getId());
				if(dbUpdateDoc(repos, parentDoc, false) == false)
				{
					System.out.println("checkAddUpdateParentDoc 更新父节点版本号失败: " + parentDoc.getDocId() + " " + parentDoc.getPath() + parentDoc.getName());	
				}
			}
		}
	}

	//底层deleteDoc接口
	protected String deleteDoc(Repos repos, Doc doc, String commitMsg,String commitUser, User login_user, ReturnAjax rt, List<CommonAction> actionList) 
	{
		switch(repos.getType())
		{
		case 1:
		case 2:
		case 3:
		case 4:
			return deleteDoc_FSM(repos, doc, commitMsg, commitUser, login_user,  rt, actionList);			
		}
		return null;
	}

	protected String deleteDoc_FSM(Repos repos, Doc doc,	String commitMsg,String commitUser,User login_user, ReturnAjax rt, List<CommonAction> actionList) 
	{
		Long docId = doc.getDocId();
		if(docId == 0)
		{
			//由于前台是根据docId和pid来组织目录结构的，所以前台可以删除docId=0的节点，表示数据库中存在一个docId=0的非法节点，直接删除掉
			docSysDebugLog("deleteDoc_FSM() 这是一个非法节点docId = 0", rt);
			dbDeleteDoc(repos, doc, false);
			return null;
		}
		
		DocLock docLock = null;
		synchronized(syncLock)
		{							
			//Try to lock the Doc
			docLock = lockDoc(doc,2, 2*60*60*1000,login_user,rt,true);	//lock 2 Hours 2*60*60*1000
			if(docLock == null)
			{
				unlock(); //线程锁
				docSysDebugLog("deleteDoc_FSM() Failed to lock Doc: " + docId, rt);
				return null;			
			}
			unlock(); //线程锁
		}
		System.out.println("deleteDoc_FSM() " + docId + " " + doc.getName() + " Lock OK");
		

		//Build ActionList for RDocIndex/VDoc/VDocIndex/VDocVerRepos delete
		BuildMultiActionListForDocDelete(actionList, repos, doc, commitMsg, commitUser,true);
		
		//get RealDoc Full ParentPath
		if(deleteRealDoc(repos,doc,rt) == false)
		{
			unlockDoc(doc,login_user,docLock);
			
			docSysDebugLog("deleteDoc_FSM() deleteRealDoc Failed", rt);
			docSysErrorLog(doc.getName() + " 删除失败！", rt);
			return null;
		}
		

		String revision = verReposDocCommit(repos, false, doc, commitMsg,commitUser,rt, true, null, 2, null);
		if(revision == null)
		{
			docSysDebugLog("deleteDoc_FSM() verReposRealDocDelete Failed", rt);
			docSysWarningLog("verReposRealDocDelete Failed", rt);
		}
		else
		{
			//Delete DataBase Record and Build AsynActions For delete 
			if(dbDeleteDocEx(actionList, repos, doc, commitMsg, commitUser, true) == false)
			{	
				docSysWarningLog("不可恢复系统错误：dbDeleteDoc Failed", rt);
			}
			
			dbCheckAddUpdateParentDoc(repos, doc, null);
			//Insert Push Action
			insertCommonAction(actionList, repos, doc, null, commitMsg, commitUser, ActionType.VERREPOS, Action.PUSH, DocType.REALDOC, null, login_user);
		}
		
		unlockDoc(doc,login_user,null);
		
		rt.setData(doc);
		return revision;
	}
	
	private void BuildMultiActionListForDocAdd(List<CommonAction> actionList, Repos repos, Doc doc, String commitMsg, String commitUser) 
	{
		if(repos.getType() != 1)
		{
			//Insert index add action for RDoc Name
			insertCommonAction(actionList, repos, doc, null, commitMsg, commitUser, ActionType.INDEX, Action.ADD, DocType.DOCNAME, null, null);
		}	
		//Insert index add action for RDoc
		insertCommonAction(actionList, repos, doc, null, commitMsg, commitUser, ActionType.INDEX, Action.ADD, DocType.REALDOC, null, null);
		
		String content = doc.getContent();
		if(content == null || content.isEmpty())
		{
			return;
		}
		
		//Insert add action for VDoc
		//Build subActionList
		List<CommonAction> subActionList = new ArrayList<CommonAction>();
		if(repos.getVerCtrl1() > 0)
		{
			insertCommonAction(subActionList, repos, doc, null, commitMsg, commitUser, ActionType.VERREPOS, Action.ADD, DocType.VIRTURALDOC, null, null); //verRepos commit
		}
		insertCommonAction(subActionList, repos, doc, null, commitMsg, commitUser, ActionType.INDEX, Action.ADD, DocType.VIRTURALDOC, null, null);	//Add Index For VDoc
		
		//Insert add action for VDoc
		insertCommonAction(actionList, repos, doc, null, commitMsg, commitUser, ActionType.FS, Action.ADD, DocType.VIRTURALDOC, subActionList, null);			
	}

	protected void BuildMultiActionListForDocDelete(List<CommonAction> actionList, Repos repos, Doc doc, String commitMsg, String commitUser, boolean deleteSubDocs) 
	{	
		if(deleteSubDocs == true)
		{
			List<Doc> subDocList = docSysGetSubDocList(repos, doc);
			if(subDocList != null)
			{
				for(int i=0; i<subDocList.size(); i++)
				{
					Doc subDoc = subDocList.get(i);
					BuildMultiActionListForDocDelete(actionList, repos, subDoc, commitMsg, commitUser, deleteSubDocs);
				}
			}
		}	

		//Insert index add action for RDoc Name
		if(repos.getType() != 1)
		{
			insertCommonAction(actionList, repos, doc, null, commitMsg, commitUser, ActionType.INDEX, Action.DELETE, DocType.DOCNAME, null, null);
		}
		//Insert index delete action for RDoc
		insertCommonAction(actionList, repos, doc, null, commitMsg, commitUser, ActionType.INDEX, Action.DELETE, DocType.REALDOC, null, null);

		//Insert delete action for VDoc
		//insertCommonAction(actionList, repos, doc, null, commitMsg, commitUser, ActionType.FS, Action.DELETE, DocType.VIRTURALDOC, null);
		//Insert delete action for VDoc verRepos 
		//insertCommonAction(actionList, repos, doc, null, commitMsg, commitUser, ActionType.VERREPOS, Action.DELETE, DocType.VIRTURALDOC,, null);
		//Insert delete action for VDoc Index
		insertCommonAction(actionList, repos, doc, null, commitMsg, commitUser, ActionType.INDEX,  Action.DELETE, DocType.VIRTURALDOC, null, null);
	}
	
	private List<Doc> docSysGetSubDocList(Repos repos, Doc doc) 
	{
		switch(repos.getType())
		{
		case 1:
			//return getDBEntryList(repos, doc);			
			return getLocalEntryList(repos, doc);
		case 2:
			return getLocalEntryList(repos, doc);
		case 3:
		case 4:
			return getRemoteEntryList(repos, doc);
		}
		
		return null;
	}

	void BuildMultiActionListForDocUpdate(List<CommonAction> actionList, Repos repos, Doc doc, String reposRPath) 
	{		
		//Insert index update action for RDoc
		insertCommonAction(actionList, repos, doc, null, null, null, CommonAction.ActionType.INDEX, CommonAction.Action.UPDATE, CommonAction.DocType.REALDOC, null, null);
	}
	
	private void BuildMultiActionListForDocCopy(List<CommonAction> actionList, Repos repos, Doc srcDoc, Doc dstDoc, String commitMsg, String commitUser, boolean isMove)
	{	
		if(dstDoc.getName().isEmpty())
		{
			System.out.println("BuildMultiActionListForDocCopy() dstDoc.name is empty:" + dstDoc.getDocId() + " path:" + dstDoc.getPath() + " name:" +dstDoc.getName());
			return;
		}
		
		Action actionId = CommonAction.Action.COPY;
		if(isMove)
		{
			actionId = CommonAction.Action.MOVE;
		}
		
		//Check if dstLocalEntry exists
		String dstLocalEntryPath = dstDoc.getLocalRootPath() + dstDoc.getPath() + dstDoc.getName(); 
		File dstLocalEntry = new File(dstLocalEntryPath);
		if(dstLocalEntry.exists())
		{		
			//ActionId 1:FS 2:VerRepos 3:DB 4:Index  5:AutoSyncUp
			//ActionType 1:add 2:delete 3:update 4:move 5:copy
		    //DocType 0:DocName 1:RealDoc 2:VirtualDoc   AutoSyncUp(1: localDocChanged  2: remoteDocChanged)
			
			//Insert IndexAction For RealDoc Name Copy or Move (对于目录则会进行递归)
			if(repos.getType() != 1)
			{
				if(isMove)
				{
					insertCommonAction(actionList, repos, srcDoc, dstDoc, commitMsg, commitUser, CommonAction.ActionType.INDEX, CommonAction.Action.UPDATE, CommonAction.DocType.DOCNAME, null, null);
				}
				else	//对于copy操作则新增对该docName的索引
				{
					insertCommonAction(actionList, repos, dstDoc, null, commitMsg, commitUser, CommonAction.ActionType.INDEX, CommonAction.Action.ADD, CommonAction.DocType.DOCNAME, null, null);				
				}
			}
			
			//Insert IndexAction For RealDoc Copy or Move (对于目录则会进行递归)
			insertCommonAction(actionList, repos, srcDoc, dstDoc, commitMsg, commitUser, CommonAction.ActionType.INDEX, actionId, CommonAction.DocType.REALDOC, null, null);
			//Copy VDoc (包括VDoc VerRepos and Index)
			insertCommonAction(actionList, repos, srcDoc, dstDoc, commitMsg, commitUser, CommonAction.ActionType.FS, CommonAction.Action.COPY, DocType.VIRTURALDOC, null, null);
			insertCommonAction(actionList, repos, srcDoc, dstDoc, commitMsg, commitUser, CommonAction.ActionType.VERREPOS, CommonAction.Action.COPY, DocType.VIRTURALDOC, null, null);
			//Copy or Move VDoc (包括VDoc VerRepos and Index)
			insertCommonAction(actionList, repos, srcDoc, dstDoc, commitMsg, commitUser, CommonAction.ActionType.INDEX, actionId, DocType.VIRTURALDOC, null, null);
		}
		
		if(dstLocalEntry.isDirectory())
		{			
			//遍历本地目录，构建CommonAction
			String dstSubDocParentPath = dstDoc.getPath() + dstDoc.getName() +"/";
			String srcSubDocParentPath = srcDoc.getPath() + srcDoc.getName() +"/";
			int dstSubDocLevel = getSubDocLevel(dstDoc);
			int srcSubDocLevel = getSubDocLevel(srcDoc);
			String localRootPath = dstDoc.getLocalRootPath();
			String localVRootPath = dstDoc.getLocalVRootPath();
			
			File[] localFileList = dstLocalEntry.listFiles();
	    	for(int i=0;i<localFileList.length;i++)
	    	{
	    		File file = localFileList[i];
	    		int type = file.isDirectory()? 2:1;
	    		long size = file.length();
	    		String name = file.getName();
	    		System.out.println("BuildMultiActionListForDocCopy subFile:" + name);

	    		Doc dstSubDoc = buildBasicDoc(repos.getId(), null, dstDoc.getDocId(), dstSubDocParentPath, name, dstSubDocLevel, type, true, localRootPath, localVRootPath, size, "");
	    		dstSubDoc.setCreateTime(dstLocalEntry.lastModified());
	    		dstSubDoc.setLatestEditTime(dstLocalEntry.lastModified());
	    		
	    		Doc srcSubDoc = buildBasicDoc(repos.getId(), null, srcDoc.getDocId(), srcSubDocParentPath, name, srcSubDocLevel, type, true, localRootPath, localVRootPath, size, "");
	    		BuildMultiActionListForDocCopy(actionList, repos, srcSubDoc, dstSubDoc, commitMsg, commitUser, isMove);
	    	}
		}		
	}
		
	protected boolean executeCommonActionList(List<CommonAction> actionList, ReturnAjax rt) 
	{
		if(actionList == null || actionList.size() == 0)
		{
			return true;
		}
		
		int size = actionList.size();
		System.out.println("executeCommonActionList size:" + size);
		
		int count = 0;

		for(int i=0; i< size; i++)
		{
			CommonAction action = actionList.get(i);
						
			if(executeCommonAction(action, rt) == true)
			{
				//Execute SubActionList
				executeCommonActionList(action.getSubActionList(), rt);
				count++;
			}
		}
		
		if(count != size)
		{
			System.out.println("executeCommonActionList() failed actions:" + (size - count));	
			return false;
		}
		
		return true;
	}
	
	private boolean executeCommonAction(CommonAction action, ReturnAjax rt) {
		
		boolean ret = false;
		
		Doc srcDoc = action.getDoc();
		
		System.out.println("executeCommonAction actionType:" + action.getAction() + " docType:" + action.getDocType() + " actionId:" + action.getType() + " doc:"+ srcDoc.getDocId() + " " + srcDoc.getPath() + srcDoc.getName());

		switch(action.getType())
		{
		case FS:
			ret = executeFSAction(action, rt);
			break;
		case VERREPOS:
			String revision = executeVerReposAction(action, rt);
			if(revision != null)
			{
				action.getDoc().setRevision(revision);
				ret = true;
			}
			break;
		case DB:
			ret = executeDBAction(action, rt);
			break;			
		case INDEX:
			ret = executeIndexAction(action, rt);
			break;
		case AUTOSYNCUP: //AutoSyncUp
			ret = executeSyncUpAction(action, rt);
			break;
		default:
			break;
		}
		
		return ret;
	}

	protected boolean executeUniqueCommonActionList(List<CommonAction> actionList, ReturnAjax rt) 
	{
		System.out.println("********** executeUniqueCommonActionList ***********");
		if(actionList.size() <= 0)
		{
			System.out.println("********** executeUniqueCommonActionList actionList is empty ***********");			
			return false;
		}
		
		//Inset ActionList to uniqueCommonAction
		for(int i=0; i<actionList.size(); i++)
		{
			insertUniqueCommonAction(actionList.get(i));
		}
		
		//注意：ActionList中的doc必须都是同一个仓库下的，否则下面的逻辑会有问题
		Integer reposId = actionList.get(0).getDoc().getVid(); //get the reposId from the first doc in action list
		System.out.println("executeUniqueCommonActionList reposId:" + reposId);
		
		UniqueAction uniqueAction = uniqueActionHashMap.get(reposId);
		if(uniqueAction == null)
		{
			System.out.println("executeUniqueCommonActionList uniqueAction for " + reposId+ " is null");
			return false;
		}
		
		if(uniqueAction.getIsRunning())
		{
			System.out.println("executeUniqueCommonActionList uniqueCommonAction for " + reposId+ " is Running");
			Long expireTime = uniqueAction.getExpireTimeStamp();
			if(expireTime == null)
			{
				return true;
			}
			
			//检查是否运行超时
			long curTime = new Date().getTime();
			if(curTime < expireTime)	//
			{
				return true;
			}
			
			System.out.println("executeUniqueCommonActionList uniqueCommonAction for " + reposId+ " Running timeout, clear uniqueAction");
			
			//清空uniqueAction
			uniqueAction.setIsRunning(false);
			uniqueAction.setExpireTimeStamp(null);
			uniqueAction.getUniqueCommonActionHashMap().clear();
			uniqueAction.getUniqueCommonActionList().clear();	
			return false;
		}

		long curTime = new Date().getTime();
		uniqueAction.setExpireTimeStamp(curTime + 43200000); //12 Hours 12*60*60*1000 = 43200,000
		uniqueAction.setIsRunning(true);
		ConcurrentHashMap<Long, CommonAction> hashMap = uniqueAction.getUniqueCommonActionHashMap();
		List<CommonAction> list = uniqueAction.getUniqueCommonActionList();
		while(hashMap.size() > 0)
		{
			if(actionList.size() > 0)
			{
				CommonAction action = list.get(0);
				long docId = action.getDoc().getDocId();
				executeCommonAction(action, rt);
				list.remove(0);
				hashMap.remove(docId);
			}
			else
			{
				System.out.println("executeUniqueCommonActionList() hashMap 和 list不同步，强制清除 actionHashMap");
			}
		}
		
		//清空uniqueAction
		uniqueAction.setIsRunning(false);
		uniqueAction.setExpireTimeStamp(null);
		uniqueAction.getUniqueCommonActionHashMap().clear();
		uniqueAction.getUniqueCommonActionList().clear();	
		System.out.println("executeUniqueCommonActionList completed for " + reposId);
		return true;
	}	
	
	private boolean executeSyncUpAction(CommonAction action, ReturnAjax rt) {
		printObject("executeSyncUpAction() action:",action);
		return syncupForDocChange(action, rt);
	}

	//这个接口要保证只有一次Commit操作
	private boolean syncupForDocChange(CommonAction action, ReturnAjax rt) {		
		Doc doc = action.getDoc();
		if(doc == null)
		{
			return false;
		}
		printObject("**************************** 启动自动同步  syncupForDocChange() doc:",doc);
		
		User login_user = action.getUser();
		if(login_user == null)
		{
			login_user = new User();
			login_user.setId(0); //系统自动同步用户 AutoSync
			login_user.setName("AutoSync");
		}
		
		//文件管理系统
		HashMap<Long, DocChange> localChanges = new HashMap<Long, DocChange>();
		HashMap<Long, DocChange> remoteChanges = new HashMap<Long, DocChange>();
		Integer subDocSyncupFlag = 1;
		if(action.getAction() == Action.SYNC || action.getAction() == Action.FORCESYNC)
		{
			subDocSyncupFlag = 2;
		}
		
		Repos repos = action.getRepos();
		printObject("syncupForDocChange repos:", repos);
		
		//文件管理系统类型需要进行RealDoc的同步
		boolean realDocSyncResult = false;
		if(repos.getType() == 1)	
		{
			if(repos.getIsRemote() == 1)
			{
				//Sync Up local VerRepos with remote VerRepos
				verReposPullPush(repos, true, null);
			}
			
			Doc localEntry = fsGetDoc(repos, doc);
			if(localEntry == null)
			{
				System.out.println("syncupForDocChange() 本地文件信息获取异常:" + doc.getDocId() + " " + doc.getPath() + doc.getName());
				return false;
			}
			Doc remoteEntry = verReposGetDoc(repos, doc, null);
			if(remoteEntry == null)
			{
				System.out.println("syncupForDocChange() 远程文件信息获取异常:" + doc.getDocId() + " " + doc.getPath() + doc.getName());
				return false;
			}
			
			Doc dbDoc = dbGetDoc(repos, doc, false);
			
			boolean ret = syncupScanForDoc_FSM(repos, doc, dbDoc, localEntry, remoteEntry, login_user, rt, remoteChanges, localChanges, subDocSyncupFlag);
	
			System.out.println("syncupForDocChange() syncupScanForDoc_FSM ret:" + ret);
			if(remoteChanges.size() == 0)
			{
				System.out.println("**************************** syncupForDocChange() 远程没有改动");
			}
			else
			{
				//Do Remote SyncUp
				syncupRemoteChanges_FSM(repos, login_user, remoteChanges, rt);
			}
			
			if(localChanges.size() == 0)
			{
				System.out.println("**************************** 结束自动同步 syncupForDocChange() 本地没有改动");
				realDocSyncResult = true;
			}
			else
			{
				if(action.getAction() == Action.UNDEFINED)
				{
					System.out.println("**************************** 结束自动同步 Action:" + action.getAction() + " 本地有改动不进行同步 ");			
					realDocSyncResult = true;
				}	
				else
				{
					//Do local SyncUp
					realDocSyncResult =  syncupLocalChanges_FSM(repos, doc, action.getCommitMsg(), action.getCommitUser(), login_user, localChanges, subDocSyncupFlag, rt);
				}
			}
		}
		else
		{
			System.out.println("**************************** 结束自动同步 syncupForDocChange() 前置类型仓库不需要同步:" + repos.getType());
			realDocSyncResult = true;
		}
		
		if(action.getAction() == Action.FORCESYNC)
		{
			if(doc.getDocId() == 0)
			{
				//Delete All Index Lib
				deleteDocNameIndexLib(repos);
				deleteRDocIndexLib(repos);
				deleteVDocIndexLib(repos);
				//Build All Index For Doc
				buildIndexForDoc(repos, doc, null, null, rt, subDocSyncupFlag);
			}
			else
			{
				rebuildIndexForDoc(repos, doc, null, null, rt, subDocSyncupFlag);
			}
		}
		else
		{
			rebuildIndexForDoc(repos, doc, remoteChanges, localChanges, rt, subDocSyncupFlag);	
		}
		return realDocSyncResult;
	}
	
	private boolean buildIndexForDoc(Repos repos, Doc doc, HashMap<Long, DocChange> remoteChanges,
			HashMap<Long, DocChange> localChanges, ReturnAjax rt, Integer subDocSyncupFlag) 
	{	
		if(isDocInChangeList(doc, remoteChanges) || isDocInChangeList(doc, remoteChanges))
		{
			addIndexForDocName(repos, doc, rt);
			addIndexForRDoc(repos, doc);
			addIndexForVDoc(repos, doc);
		}
		
		if(doc.getType() == null || doc.getType() != 2)
		{
			return true;
		}
		
		//子目录不递归
		if(subDocSyncupFlag == 0)
		{
			return true;
		}

		//子目录递归不继承
		if(subDocSyncupFlag == 1)
		{
			subDocSyncupFlag = 0;
		}
		
		List<Doc> localEntryList = getLocalEntryList(repos, doc);
		//printObject("SyncUpSubDocs_FSM() localEntryList:", localEntryList);
    	if(localEntryList == null)
    	{
    		System.out.println("buildIndexForDoc() localEntryList 获取异常:");
        	return false;
    	}
    	
    	for(int i=0; i< localEntryList.size(); i++)
    	{
    		Doc subDoc = localEntryList.get(i);
    		buildIndexForDoc(repos, subDoc, remoteChanges, localChanges, rt, subDocSyncupFlag);
    	}
		return true;
	}
	
	private boolean rebuildIndexForDoc(Repos repos, Doc doc, HashMap<Long, DocChange> remoteChanges,
			HashMap<Long, DocChange> localChanges, ReturnAjax rt, Integer subDocSyncupFlag) 
	{	
		if(isDocInChangeList(doc, remoteChanges) || isDocInChangeList(doc, remoteChanges))
		{
			//Refresh Index For DocName
			deleteIndexForDocName(repos, doc, rt);
			addIndexForDocName(repos, doc, rt);
			
			//Refresh Index For RealDoc
			deleteIndexForRDoc(repos, doc);
			addIndexForRDoc(repos, doc);
			
			//Refresh Index For VDoc
			deleteIndexForVDoc(repos, doc);
			addIndexForVDoc(repos, doc);
		}
		
		if(doc.getType() == null || doc.getType() != 2)
		{
			return true;
		}
		
		//子目录不递归
		if(subDocSyncupFlag == 0)
		{
			return true;
		}

		//子目录递归不继承
		if(subDocSyncupFlag == 1)
		{
			subDocSyncupFlag = 0;
		}
		
		List<Doc> localEntryList = getLocalEntryList(repos, doc);
		//printObject("SyncUpSubDocs_FSM() localEntryList:", localEntryList);
    	if(localEntryList == null)
    	{
    		System.out.println("refreshIndexForDoc() localEntryList 获取异常:");
        	return false;
    	}
    	
    	for(int i=0; i< localEntryList.size(); i++)
    	{
    		Doc subDoc = localEntryList.get(i);
    		rebuildIndexForDoc(repos, subDoc, remoteChanges, localChanges, rt, subDocSyncupFlag);
    	}
		return true;
	}

	private boolean isDocInChangeList(Doc doc, HashMap<Long, DocChange> docChanges) 
	{
		if(docChanges == null)
		{
			return true;
		}
		
		if(docChanges.get(doc.getDocId()) != null)
		{
			return true;	
		}
		return false;
	}

	private boolean syncupRemoteChanges_FSM(Repos repos, User login_user, HashMap<Long, DocChange> remoteChanges, ReturnAjax rt) 
	{
		for(DocChange docChange: remoteChanges.values())
	    {
			syncUpRemoteChange_FSM(repos, docChange, login_user, rt);
	    }
		return true;
	}

	private boolean syncupLocalChanges_FSM(Repos repos, Doc doc, String commitMsg, String commitUser, User login_user, HashMap<Long, DocChange> localChanges, Integer subDocSyncupFlag, ReturnAjax rt) {
		//本地有改动需要提交
		System.out.println("syncupForDocChange() 本地有改动: [" + doc.getPath()+doc.getName() + "], do Commit");
		if(commitMsg == null)
		{
			commitMsg = "自动同步 ./" +  doc.getPath()+doc.getName();
		}
		if(commitUser == null)
		{
			commitUser = login_user.getName();
		}
		
		//LockDoc
		DocLock docLock = null;
		synchronized(syncLock)
		{
			//Try to lock the Doc
			docLock = lockDoc(doc,2,1*60*60*1000,login_user,rt,true); //2 Hours 2*60*60*1000 = 86400,000
			if(docLock == null)
			{
				unlock(); //线程锁
				docSysDebugLog("syncupForDocChange() Failed to lock Doc: " + doc.getName(), rt);
				System.out.println("**************************** 结束自动同步 syncupForDocChange() 文件已被锁定:" + doc.getDocId() + " [" + doc.getPath() + doc.getName() + "]");
				return false;
			}
			unlock(); //线程锁
		}
		
		List<CommitAction> commitActionList = new ArrayList<CommitAction>();
		String revision = verReposDocCommit(repos, false, doc, commitMsg, commitUser, rt, true, localChanges, subDocSyncupFlag, commitActionList);
		if(revision == null)
		{
			System.out.println("**************************** 结束自动同步 syncupForDocChange() 本地改动Commit失败:" + revision);
			unlockDoc(doc, login_user, docLock);
			return false;
		}
		//推送到远程仓库
		verReposPullPush(repos, true, rt);
		
		if(commitActionList != null)
		{
			for(int i=0; i<commitActionList.size(); i++)
			{
				Doc commitDoc = commitActionList.get(i).getDoc();
				printObject("syncupForDocChange() dbUpdateDoc commitDoc: ", commitDoc);						
				//需要根据commitAction的行为来决定相应的操作
				commitDoc.setRevision(revision);
				commitDoc.setLatestEditorName(login_user.getName());
				dbUpdateDoc(repos, commitDoc, true);
				dbCheckAddUpdateParentDoc(repos, commitDoc, null);
			}			
			dbUpdateDocRevision(repos, doc, revision);
		}
		
		System.out.println("**************************** 结束自动同步 syncupForDocChange() 本地改动已更新:" + revision);
		unlockDoc(doc, login_user, docLock);
		
		return true;	
	}

	private boolean syncupForDocChange_NoFS(Repos repos, Doc doc, User login_user, ReturnAjax rt, int subDocSyncFlag) 
	{
		Doc remoteEntry = verReposGetDoc(repos, doc, null);
		if(remoteEntry == null)
		{
			docSysDebugLog("syncupForDocChange_NoFS() remoteEntry is null for " + doc.getPath()+doc.getName() + ", 无法同步！", rt);
			return true;
		}
		
		printObject("syncupForDocChange_NoFS() remoteEntry: ", remoteEntry);
		
		Doc dbDoc = dbGetDoc(repos, doc, false);
		printObject("syncupForDocChange_NoFS() dbDoc: ", dbDoc);

		
		DocChangeType remoteChangeType = getRemoteChangeType(repos, dbDoc, remoteEntry);
		if(remoteChangeType != DocChangeType.NOCHANGE)
		{
			//LockDoc
			DocLock docLock = null;
			synchronized(syncLock)
			{
				//Try to lock the Doc
				docLock = lockDoc(doc,2,1*60*60*1000,login_user,rt,true); //2 Hours 2*60*60*1000 = 86400,000
				if(docLock == null)
				{
					unlock(); //线程锁
					docSysDebugLog("syncupForDocChange() Failed to lock Doc: " + doc.getName(), rt);
					return false;
				}
				unlock(); //线程锁
			}
			boolean ret = syncUpForRemoteChange_NoFS(repos, dbDoc, remoteEntry, login_user, rt, remoteChangeType);
			unlockDoc(doc, login_user, docLock);
			return ret;
		}
		
		return SyncUpSubDocs_NoFS(repos, doc, login_user, rt, subDocSyncFlag);
	}
	
	
	private boolean SyncUpSubDocs_NoFS(Repos repos, Doc doc, User login_user, ReturnAjax rt, int subDocSyncFlag) 
	{
		//子目录不递归
		if(subDocSyncFlag == 0)
		{
			return true;
		}
		
		//子目录递归不继承
		if(subDocSyncFlag == 1)
		{
			subDocSyncFlag = 0;
		}
		
		if(isRemoteDocChanged(repos, doc) == false)
		{
			//No Change
			return true;
		}

		HashMap<String, Doc> docHashMap = new HashMap<String, Doc>();	//the doc already syncUped
		
		Doc subDoc = null;
		List<Doc> dbDocList = getDBEntryList(repos, doc);
	   	if(dbDocList != null)
    	{
	    	for(int i=0;i<dbDocList.size();i++)
	    	{
	    		subDoc = dbDocList.get(i);
	    		docHashMap.put(subDoc.getName(), subDoc);
	    		syncupForDocChange_NoFS(repos, subDoc, login_user, rt, subDocSyncFlag);
	    	}
    	}
	    
	    List<Doc> remoteEntryList = getRemoteEntryList(repos, doc);
	    //printObject("SyncUpSubDocs_FSM() remoteEntryList:", remoteEntryList);
	    if(remoteEntryList != null)
    	{
	    	for(int i=0;i<remoteEntryList.size();i++)
		    {
	    		subDoc = remoteEntryList.get(i);
	    		if(docHashMap.get(subDoc.getName()) != null)
	    		{
	    			//already syncuped
	    			continue;	
	    		}
	    		
	    		docHashMap.put(subDoc.getName(), subDoc);
	    		syncupForDocChange_NoFS(repos, subDoc, login_user, rt, subDocSyncFlag);
		    }
    	}
	    
	    return true;
	}
	
	private boolean isRemoteDocChanged(Repos repos, Doc doc) 
	{
		Doc dbDoc = dbGetDoc(repos, doc, false);
    	if(dbDoc == null || dbDoc.getRevision() == null)
    	{
    		return true;
    	}
    	
    	String latestRevision = verReposGetLatestRevision(repos, false, doc);
        System.out.println("isRemoteDocChanged() latestRevision:" + latestRevision + " doc:" + doc.getDocId() + " [" + doc.getPath() + doc.getName() + "]");
        System.out.println("isRemoteDocChanged() previoRevision:" + dbDoc.getRevision());
        
        if(latestRevision == null || dbDoc.getRevision().equals(latestRevision) == false)
        {
        	return true;
        }
    	
    	return false;
	}

	private boolean syncUpForRemoteChange_NoFS(Repos repos, Doc doc, Doc remoteEntry, User login_user, ReturnAjax rt, DocChangeType remoteChangeType) 
	{
		switch(remoteChangeType)
		{
		case REMOTEADD:
			System.out.println("syncUpForRemoteChange_NoFS() remote Added: " + doc.getPath()+doc.getName());
			return dbAddDoc(repos, remoteEntry, false, false);
		case REMOTEFILETODIR:
		case REMOTEDIRTOFILE:
			System.out.println("syncUpForRemoteChange_NoFS() remote Type Changed: " + doc.getPath()+doc.getName());
			dbDeleteDoc(repos, doc,true);
			return dbAddDoc(repos, remoteEntry, true, false);
		case REMOTECHANGE:
			System.out.println("syncUpForRemoteChange_NoFS() remote File Changed: " + doc.getPath()+doc.getName());
			doc.setRevision(remoteEntry.getRevision());
			return dbUpdateDoc(repos, doc, true);
		case REMOTEDELETE:
			//Remote Deleted
			System.out.println("syncUpForRemoteChange_NoFS() remote Deleted: " + doc.getPath()+doc.getName());
			return dbDeleteDoc(repos, doc, true);
		default:
			break;
		}
		
		return true;
	}
	
	protected String buildChangeInfo(HashMap<Long, DocChange> ChangeList) 
	{
		String changeInfo = "";
		if(ChangeList == null || ChangeList.size() == 0)
		{
			return "";
		}

		for(DocChange docChange: ChangeList.values())
	    {
			Doc doc = docChange.getDoc();
			switch(docChange.getType())
			{
			case LOCALADD:	//localAdd
				changeInfo += "本地新增 " + doc.getPath() + doc.getName() + "</br>";
				break;
			case LOCALDELETE: 	//localDelete
				changeInfo += "本地删除 " + doc.getPath() + doc.getName() + "</br>";
				break;
			case LOCALCHANGE: 	//localFileChanged
				changeInfo += "本地修改 " + doc.getPath() + doc.getName() + "</br>";
				break;
			case LOCALFILETODIR:	//localTypeChanged(From File to Dir)
				changeInfo += "本地文件类型变动(文件->目录) " + doc.getPath() + doc.getName() + "</br>";
				break;
			case LOCALDIRTOFILE:	//localTypeChanged(From Dir to File)
				changeInfo += "本地文件类型变动(目录->文件) " + doc.getPath() + doc.getName() + "</br>";
				break;
			//由于远程同步需要直接修改或删除本地文件，一旦误操作将无法恢复，必须保证删除修改操作的文件的历史已经在版本仓库中
			case REMOTEDELETE:	//remoteDelete
				changeInfo += "远程删除 " + doc.getPath() + doc.getName() + "</br>";
				break;
			case REMOTECHANGE:	//remoteFileChanged
				changeInfo += "远程修改 " + doc.getPath() + doc.getName() + "</br>";
				break;
			case REMOTEFILETODIR:	//remoteTypeChanged(From File To Dir)
				changeInfo += "远程文件类型变动(文件->目录) " + doc.getPath() + doc.getName() + "</br>";
				break;
			case REMOTEDIRTOFILE:	//remoteTypeChanged(From Dir To File)
				changeInfo += "远程文件类型变动(目录->文件) " + doc.getPath() + doc.getName() + "</br>";
				break;
			case REMOTEADD:	//remoteAdd
				changeInfo += "远程新增 " + doc.getPath() + doc.getName() + "</br>";
			case NOCHANGE:		//no change
				break;
			default:
				changeInfo += "未知变动(" +docChange.getType() + ") "  + doc.getPath() + doc.getName() + "</br>";
				break;
			}		
		}
		return changeInfo;
	}
	
	protected boolean syncupScanForDoc_FSM(Repos repos, Doc doc, Doc dbDoc, Doc localEntry, Doc remoteEntry, User login_user, ReturnAjax rt, HashMap<Long, DocChange> remoteChanges, HashMap<Long, DocChange> localChanges, int subDocSyncFlag) 
	{
		//printObject("syncupForDocChange_FSM() " + doc.getDocId() + " " + doc.getPath() + doc.getName() + " ", doc);

		if(doc.getDocId() == 0)	//For root dir, go syncUpSubDocs
		{
			System.out.println("syncupForDocChange_FSM() 同步根目录");			
			return syncupScanForSubDocs_FSM(repos, doc, login_user, rt, remoteChanges, localChanges, subDocSyncFlag);
		}
		
		DocChangeType docChangeType = getDocChangeType_FSM(repos, doc, dbDoc, localEntry, remoteEntry);
		//System.out.println("syncupForDocChange_FSM() docChangeType: " + docChangeType);
		
		switch(docChangeType)
		{
		case LOCALADD:	//localAdd
		case LOCALDELETE: 	//localDelete
		case LOCALCHANGE: 	//localFileChanged
		case LOCALFILETODIR:	//localTypeChanged(From File to Dir)
		case LOCALDIRTOFILE:	//localTypeChanged(From Dir to File)
			DocChange localChange = new DocChange();
			localChange.setDoc(doc);
			localChange.setDbDoc(dbDoc);
			localChange.setLocalEntry(localEntry);
			localChange.setRemoteEntry(remoteEntry);
			localChange.setType(docChangeType);
			localChanges.put(doc.getDocId(), localChange);
			return true;
		//由于远程同步需要直接修改或删除本地文件，一旦误操作将无法恢复，必须保证删除修改操作的文件的历史已经在版本仓库中
		case REMOTEDELETE:	//remoteDelete
		case REMOTECHANGE:	//remoteFileChanged
		case REMOTEFILETODIR:	//remoteTypeChanged(From File To Dir)
		case REMOTEDIRTOFILE:	//remoteTypeChanged(From Dir To File)
			if(isDocInVerRepos(repos, doc, dbDoc.getRevision()) == false)
			{
				System.out.println("syncupForDocChange_FSM() " + doc.getPath()+doc.getName() + " not exists in verRepos at revision:" + dbDoc.getRevision() + " treat it as LOCALCHANGE");
				DocChange localChange1 = new DocChange();
				localChange1.setDoc(doc);
				localChange1.setDbDoc(dbDoc);
				localChange1.setLocalEntry(localEntry);
				localChange1.setRemoteEntry(remoteEntry);
				localChange1.setType(DocChangeType.LOCALCHANGE);	//LOCALCHANGE才能保证在AutoCommit的时候正常工作
				localChanges.put(dbDoc.getDocId(), localChange1);
				return true;
			}
		case REMOTEADD:	//remoteAdd
			DocChange remoteChange = new DocChange();
			remoteChange.setDoc(doc);
			remoteChange.setDbDoc(dbDoc);
			remoteChange.setLocalEntry(localEntry);
			remoteChange.setRemoteEntry(remoteEntry);
			remoteChange.setType(docChangeType);
			remoteChanges.put(doc.getDocId(), remoteChange);
			return true;
		case NOCHANGE:		//no change
			if(dbDoc != null && dbDoc.getType() == 2)
			{
				return syncupScanForSubDocs_FSM(repos, doc, login_user, rt, remoteChanges, localChanges, subDocSyncFlag);
			}
			return true;
		default:
			break;
		}		
		return false;
	}
	
	private boolean isDocInVerRepos(Repos repos, Doc doc, String commitId) {
		
		if(commitId == null || commitId.isEmpty())
		{
			return false;
		}
		
		Integer type = verReposCheckPath(repos, false, doc, commitId);
		if(type == null || type <= 0)
		{
			return false;
		}
		
		return true;
	}
	
	protected DocChangeType getDocChangeType_FSM(Repos repos,Doc doc, Doc dbDoc, Doc localEntry, Doc remoteEntry) 
	{						
		//dbDoc不存在
		if(dbDoc == null)
		{
			//System.out.println("getDocChangeType_FSM() dbDoc 不存在, localEntry存在");
			if(localEntry != null && localEntry.getType() != 0)
			{
				//本地新增文件/目录
				System.out.println("getDocChangeType_FSM() 本地新增:" + doc.getDocId() + " " + doc.getPath() + doc.getName() + " dbDoc不存在 localEntry存在" );
				return DocChangeType.LOCALADD;
			}
			
			if(remoteEntry != null && remoteEntry.getType() != 0)
			{
				//远程文件/目录新增
				System.out.println("getDocChangeType_FSM() 远程新增:" + doc.getDocId() + " " + doc.getPath() + doc.getName() + " dbDoc不存在 localEntry不存在 remoteEntry存在");
				return DocChangeType.REMOTEADD;
			}
			
			//未变更
			//System.out.println("getDocChangeType_FSM() 未变更(dbDoc不存在/localDoc不存在/remoteDoc不存在):" + doc.getDocId() + " " + doc.getPath() + doc.getName());
			return DocChangeType.NOCHANGE;
		}
		
		//dbDoc存在
		if(localEntry == null || localEntry.getType() == 0)
		{
			DocChangeType remoteChangeType = getRemoteChangeType(repos, dbDoc, remoteEntry);
			if(remoteChangeType == DocChangeType.NOCHANGE || remoteChangeType == DocChangeType.REMOTEDELETE)
			{
				//本地文件/目录删除
				System.out.println("getDocChangeType_FSM() 本地删除:" + doc.getDocId() + " " + doc.getPath() + doc.getName() + " dbDoc存在 localEntry不存在");
				return DocChangeType.LOCALDELETE;
			}
			
			//远程文件/目录 类型变化、内容修改、删除
			//System.out.println("getDocChangeType_FSM() 远程类型变化/内容修改/删除:" + doc.getDocId() + " " + doc.getPath() + doc.getName());
			return remoteChangeType;
		}
		
		//dbDoc存在，localEntry存在且是文件
		if(localEntry.getType() == 1)
		{
			if(dbDoc.getType() == 2)
			{
				//本地目录 类型变化 （目录删除后新增同名文件）
				System.out.println("getDocChangeType_FSM() 本地类型变化（目录->文件）:" + doc.getDocId() + " " + doc.getPath() + doc.getName() + "dbDoc是目录, localEntry是文件");
				return DocChangeType.LOCALDIRTOFILE;
			}
			
			if(isDocLocalChanged(dbDoc, localEntry))
			{
				//本地文件 内容修改
				System.out.println("getDocChangeType_FSM() 本地文件修改:" + doc.getDocId() + " " + doc.getPath() + doc.getName() + " dbDoc和localEntry是文件");
				return DocChangeType.LOCALCHANGE;
			}
			
			if(remoteEntry == null || remoteEntry.getType() == 0)
			{
				//远程删除
				System.out.println("getDocChangeType_FSM() 远程删除:" + doc.getDocId() + " " + doc.getPath() + doc.getName() + " dbDoc和localEntry是文件且一致, remoteEntry不存在");
				return DocChangeType.REMOTEDELETE;
			}
			
			if(remoteEntry.getType() == 2)
			{
				//远程文件 类型变化（文件被删除并增加了同名目录）
				System.out.println("getDocChangeType_FSM() 远程类型改变（文件->目录）:" + doc.getDocId() + " " + doc.getPath() + doc.getName() + " dbDoc和localEntry是文件且一致, remoteEntry是目录");
				return DocChangeType.REMOTEFILETODIR;
			}
			
			if(isDocRemoteChanged(repos, dbDoc, remoteEntry))
			{
				//远程文件 内容修改
				System.out.println("getDocChangeType_FSM() 远程文件修改:" + doc.getDocId() + " " + doc.getPath() + doc.getName() + " dbDoc和localEntry是文件且一致, remoteEntry是文件但不一致");
				return DocChangeType.REMOTECHANGE;
			}
			
			//未变更
			//System.out.println("getDocChangeType_FSM() 未变更(dbDoc存在/localDoc是文件/remoteDoc是文件):" + doc.getDocId() + " " + doc.getPath() + doc.getName());
			return DocChangeType.NOCHANGE;
		}
		
		//dbDoc存在，localDoc存在且是目录
		if(localEntry.getType() == 2)
		{
			if(dbDoc.getType() == 1)
			{
				//本地文件 类型变化 （文件删除后新增同名文件）
				System.out.println("getDocChangeType_FSM() 本地类型改变（文件->目录）:" + doc.getDocId() + " " + doc.getPath() + doc.getName() + " dbDoc是文件, localEntry是目录");
				return DocChangeType.LOCALFILETODIR;
			}
			
			if(remoteEntry == null || remoteEntry.getType() == 0)
			{
				if(isDirLocalChanged(repos, dbDoc))
				{
					//远程删除，但同时本地目录有修改
					System.out.println("getDocChangeType_FSM() 远程删除，但本地目录有改动:" + doc.getDocId() + " " + doc.getPath() + doc.getName() + " dbDoc/localEntry是目录但不一致, remoteEntry不存在");
					return DocChangeType.LOCALCHANGE;
				}
				
				//远程删除
				if(repos.getVerCtrl() == 2)
				{
					//GIT 仓库无法识别空目录，因此如果是空目录则认为没有改变（不存在、文件也会被认为是空目录）
					if(isEmptyDir(doc.getLocalRootPath() + doc.getPath() + doc.getName(), false))
					{
						System.out.println("getDocChangeType_FSM() 没有变化:" + doc.getDocId() + " " + doc.getPath() + doc.getName() + " dbDoc/localEntry是空目录且一致, remoteEntry不存在");
						return DocChangeType.NOCHANGE;
					}
				}
				System.out.println("getDocChangeType_FSM() 远程删除:" + doc.getDocId() + " " + doc.getPath() + doc.getName() + " dbDoc/localEntry是目录且一致, remoteEntry不存在");
				return DocChangeType.REMOTEDELETE;
			}
			
			if(remoteEntry.getType() == 1)
			{
				if(isDirLocalChanged(repos, dbDoc))
				{
					//远程目录 类型变化（目录被删除并增加了同名文件），但同时本地目录有修改
					System.out.println("getDocChangeType_FSM() 远程类型改变（目录->文件），但本地目录有改动:" + doc.getDocId() + " " + doc.getPath() + doc.getName() + " dbDoc/localEntry是目录但不一致, remoteEntry是文件");
					return DocChangeType.LOCALCHANGE;
				}
				
				//远程目录 类型变化（目录被删除并增加了同名文件）
				System.out.println("getDocChangeType_FSM() 远程类型改变（目录->文件）:" + doc.getDocId() + " " + doc.getPath() + doc.getName() + " dbDoc/localEntry是目录且一致, remoteEntry是文件");
				return DocChangeType.REMOTEDIRTOFILE;
			}
			
			//未变更
			//System.out.println("getDocChangeType_FSM() 未变更(dbDoc存在/localDoc是目录/remoteDoc是目录):" + doc.getDocId() + " " + doc.getPath() + doc.getName());
			return DocChangeType.NOCHANGE;
		}
		
		//未知文件类型(localDoc.type !=1/2)
		System.out.println("getDocChangeType_FSM() 本地未知文件类型(" + localEntry.getType()+ "):" + doc.getDocId() + " " + doc.getPath() + doc.getName());
		return DocChangeType.UNDEFINED;
	}

	private Doc getDocFromList(Doc doc, HashMap<Long, Doc> dbDocHashMap) 
	{
		if(dbDocHashMap == null || dbDocHashMap.size() == 0)
		{
			return null;
		}
		
		return dbDocHashMap.get(doc.getDocId());
	}

	protected boolean syncupScanForSubDocs_FSM(Repos repos, Doc doc, User login_user, ReturnAjax rt, HashMap<Long, DocChange> remoteChanges, HashMap<Long, DocChange> localChanges, int subDocSyncFlag) 
	{
		//System.out.println("************************ SyncUpSubDocs_FSM()  " + doc.getDocId() + " " + doc.getPath() + doc.getName() + " subDocSyncFlag:" + subDocSyncFlag);

		//子目录不递归
		if(subDocSyncFlag == 0)
		{
			return true;
		}

		//子目录递归不继承
		if(subDocSyncFlag == 1)
		{
			subDocSyncFlag = 0;
		}
		
		HashMap<Long, Doc> dbDocHashMap = null;	
		HashMap<Long, Doc> localDocHashMap =  null;	
		HashMap<Long, Doc> remoteDocHashMap = null;		

				
		List<Doc> localEntryList = getLocalEntryList(repos, doc);
		//printObject("SyncUpSubDocs_FSM() localEntryList:", localEntryList);
    	if(localEntryList == null)
    	{
    		System.out.println("SyncUpSubDocs_FSM() localEntryList 获取异常:");
        	return false;
    	}

		List<Doc> dbDocList = getDBEntryList(repos, doc);
		//printObject("SyncUpSubDocs_FSM() dbEntryList:", dbDocList);

		//注意: 如果仓库没有版本仓库则不需要远程同步
		List<Doc> remoteEntryList = null;
    	boolean isRemoteSyncUpNeed = isRemoteSyncupNeed(repos);
    	
    	if(isRemoteSyncUpNeed)
		{
    		remoteEntryList = getRemoteEntryList(repos, doc);
    	    //printObject("SyncUpSubDocs_FSM() remoteEntryList:", remoteEntryList);
        	if(remoteEntryList == null)
        	{
        		System.out.println("SyncUpSubDocs_FSM() remoteEntryList 获取异常:");
            	return false;
        	}        	
		}

    	//将dbDocList\localEntryList\remoteEntryList转成HashMap
		localDocHashMap =  ConvertDocListToHashMap(localEntryList);	
		dbDocHashMap = ConvertDocListToHashMap(dbDocList);	
		if(isRemoteSyncUpNeed)	//如果不需要远程同步则直接将remoteHashMap设置成dbHashMap来避免远程同步
		{
			remoteDocHashMap = ConvertDocListToHashMap(remoteEntryList);					
		}
		else
		{
			remoteDocHashMap = dbDocHashMap;
		}

		
		HashMap<String, Doc> docHashMap = new HashMap<String, Doc>();	//the doc already syncUped		
		syncupScanForDocList_FSM(remoteEntryList, docHashMap, repos, dbDocHashMap, localDocHashMap, remoteDocHashMap, login_user, rt, remoteChanges, localChanges, subDocSyncFlag);
		syncupScanForDocList_FSM(localEntryList, docHashMap, repos, dbDocHashMap, localDocHashMap, remoteDocHashMap, login_user, rt, remoteChanges, localChanges, subDocSyncFlag);
		syncupScanForDocList_FSM(dbDocList, docHashMap, repos, dbDocHashMap, localDocHashMap, remoteDocHashMap, login_user, rt, remoteChanges, localChanges, subDocSyncFlag);

		return true;
    }
	
	private boolean isRemoteSyncupNeed(Repos repos) {
		if(repos.getVerCtrl() == null)
		{
			return false;
		}
		
		
		if(repos.getVerCtrl() == 0)
		{	
			return false;
		}
		
//		if(repos.getIsRemote() == null)
//		{
//			return false;
//		}
//		
//		//RemoteSyncup only for the repos with remoteVerRepos
//		if(repos.getIsRemote() != 1)
//		{
//			return false;
//		}	

		return true;
	}

	boolean syncupScanForDocList_FSM(List<Doc> docList, HashMap<String, Doc> docHashMap, Repos repos, HashMap<Long, Doc> dbDocHashMap, HashMap<Long, Doc> localDocHashMap, HashMap<Long, Doc> remoteDocHashMap, User login_user, ReturnAjax rt, HashMap<Long, DocChange> remoteChanges, HashMap<Long, DocChange> localChanges, int subDocSyncFlag)
	{
		if(docList == null)
		{
			return true;
		}
		
	    for(int i=0;i<docList.size();i++)
	    {
    		Doc subDoc = docList.get(i);
    		//System.out.println("syncupDocChangeForDocList_FSM() subDoc:" + subDoc.getDocId() + " " + subDoc.getPath() + subDoc.getName());
    		
    		if(docHashMap.get(subDoc.getName()) != null)
    		{
    			//already syncuped
    			continue;	
    		}
    		
    		Doc dbDoc = getDocFromList(subDoc, dbDocHashMap);
    		//printObject("syncupForDocChange_FSM() dbDoc: ", dbDoc);

    		Doc localEntry = getDocFromList(subDoc, localDocHashMap);
    		//printObject("syncupForDocChange_FSM() localEntry: ", localEntry);
    		
    		Doc remoteEntry = getDocFromList(subDoc, remoteDocHashMap);
    		//printObject("syncupForDocChange_FSM() remoteEntry: ", remoteEntry);
    		docHashMap.put(subDoc.getName(), subDoc);
    		syncupScanForDoc_FSM(repos, subDoc, dbDoc, localEntry, remoteEntry, login_user, rt, remoteChanges, localChanges, subDocSyncFlag);
	    }
		return true;
	}
	
	private HashMap<Long, Doc> ConvertDocListToHashMap(List<Doc> docList) {
		if(docList == null)
    	{
			return null;
    	}

		HashMap<Long, Doc> docHashMap = new HashMap<Long, Doc>();

		for(int i=0;i< docList.size(); i++)
	    {
	    		Doc doc = docList.get(i);
	    		docHashMap .put(doc.getDocId(), doc);
	    }
		return docHashMap;
	}

	private boolean syncUpRemoteChange_FSM(Repos repos, DocChange docChange, User login_user, ReturnAjax rt) 
	{	
		Doc doc = docChange.getDoc();
		Doc remoteEntry = docChange.getRemoteEntry();
		DocChangeType docChangeType = docChange.getType();
		
		String localParentPath = null;
		List<Doc> successDocList = null;
		
		switch(docChangeType)
		{
		case REMOTEADD:		//Remote Added
			System.out.println("syncUpRemoteChange_FSM() remote Added: " + remoteEntry.getPath()+remoteEntry.getName());	
			localParentPath = getReposRealPath(repos) + remoteEntry.getPath();
			successDocList = verReposCheckOut(repos, false, remoteEntry, localParentPath, remoteEntry.getName(), null, true, false, null);
			if(successDocList != null)
			{
				dbAddDoc(repos, remoteEntry, true, false);
				return true;
			}
			return false;
		case REMOTEDELETE: //Remote Deleted
			System.out.println("syncUpRemoteChange_FSM() remote deleted: " + doc.getPath()+doc.getName());
			if(repos.getVerCtrl() == 1 || doc.getType() == 1) 
			{
				if(deleteRealDoc(repos, doc, rt) == true)
				{
					dbDeleteDoc(repos, doc,true);
				}
				return true;
			}
			
			if(doc.getType() == 2)	//对于GIT仓库无法区分空目录，因此只删除子目录
			{
				System.out.println("syncUpRemoteChange_FSM() Git仓库无法识别空目录，因此只删除子目录: " + doc.getPath()+doc.getName());
				deleteSubDoc(repos, doc, rt);			
			}	
			return true;
		case REMOTECHANGE: //Remote File Changed
			System.out.println("syncUpRemoteChange_FSM() remote Changed: " + doc.getPath()+doc.getName());
			
			localParentPath = getReposRealPath(repos) + remoteEntry.getPath();
			successDocList = verReposCheckOut(repos, false, remoteEntry, localParentPath, remoteEntry.getName(), null, true, false, null);
			if(successDocList != null)
			{
				//SuccessDocList中的doc包括了revision信息
				for(int i=0; i<successDocList.size(); i++)
				{
					dbUpdateDoc(repos, successDocList.get(i), true);
				}
				return true;
			}
			return false;
		case REMOTEFILETODIR: //Remote Type Changed
		case REMOTEDIRTOFILE:
			System.out.println("syncUpRemoteChange_FSM() remote Type Changed: " + doc.getPath()+doc.getName());
			if(deleteRealDoc(repos, doc, rt) == true)
			{
				dbDeleteDoc(repos, doc,true);
				
				//checkOut
				localParentPath = getReposRealPath(repos) + remoteEntry.getPath();
				successDocList = verReposCheckOut(repos, false, remoteEntry, localParentPath, remoteEntry.getName(), null, true, false, null);
				if(successDocList != null)
				{
					dbAddDoc(repos, remoteEntry, true, false);
					return true;						
				}
				return false;						
			}
			else
			{
				return false;
			}
		default:
			break;

		}
		return false;
	}

	private boolean deleteSubDoc(Repos repos, Doc doc, ReturnAjax rt) {
		List<Doc> subDocList = getLocalEntryList(repos, doc);
		for(int i=0; i< subDocList.size(); i++)
		{
			Doc subDoc = subDocList.get(i);
			if(deleteRealDoc(repos, subDoc, rt) == true)
			{
				dbDeleteDoc(repos, subDoc,true);
			}
		}
		return true;
	}

	private DocChangeType getRemoteChangeType(Repos repos, Doc dbDoc, Doc remoteEntry) 
	{
		if(repos.getVerCtrl() == null || repos.getVerCtrl() == 0)
		{
			//System.out.println("getRemoteChangeType() no verCtrl");
			return DocChangeType.NOCHANGE;
		}
		
		if(dbDoc == null)
		{
			if(remoteEntry != null && remoteEntry.getType() != 0)
			{
				System.out.println("getRemoteChangeType() 远程文件/目录新增:"+remoteEntry.getName());
				return DocChangeType.REMOTEADD;				
			}
			//System.out.println("getRemoteChangeType() 远程文件未变更");
			return DocChangeType.NOCHANGE;
		}
		
		if(remoteEntry == null ||remoteEntry.getType() == 0)
		{
			System.out.println("getRemoteChangeType() 远程文件删除:"+dbDoc.getName());
			if(repos.getVerCtrl() == 2)
			{
				//GIT 仓库无法识别空目录，因此如果是空目录则认为没有改变（不存在、文件也会被认为是空目录）
				if(isEmptyDir(dbDoc.getLocalRootPath() + dbDoc.getPath() + dbDoc.getName(), false))
				{
					return DocChangeType.NOCHANGE;
				}
			}		
			return DocChangeType.REMOTEDELETE;
		}
		
		switch(remoteEntry.getType())
		{
		case 1:
			if(dbDoc.getType() == null || dbDoc.getType() != 1)
			{
				System.out.println("getRemoteChangeType() 远程文件类型改变(目录->文件):"+remoteEntry.getName());
				return DocChangeType.REMOTEDIRTOFILE;
			}
			
			if(isDocRemoteChanged(repos, dbDoc, remoteEntry))
			{
				System.out.println("getRemoteChangeType() 远程文件内容修改:"+remoteEntry.getName());
				return DocChangeType.REMOTECHANGE;
			}
			
			//System.out.println("getRemoteChangeType() 远程文件未变更:"+remoteEntry.getName());
			return DocChangeType.NOCHANGE;
		case 2:
			if(dbDoc.getType() == null || dbDoc.getType() != 2)
			{
				System.out.println("getRemoteChangeType() 远程文件类型改变(文件->目录):"+remoteEntry.getName());
				return DocChangeType.REMOTEFILETODIR;
			}

			//System.out.println("getRemoteChangeType() 远程目录未变更:"+remoteEntry.getName());
			return DocChangeType.NOCHANGE;
		}
		
		System.out.println("getRemoteChangeType() 远程文件类型未知:"+dbDoc.getName());
		return DocChangeType.UNDEFINED;
	}

	protected Doc fsGetDoc(Repos repos, Doc doc) 
	{
		Doc localDoc = new Doc();
		localDoc.setVid(repos.getId());
		localDoc.setDocId(doc.getDocId());
		localDoc.setPid(doc.getPid());
		localDoc.setPath(doc.getPath());
		localDoc.setName(doc.getName());
		localDoc.setType(0);	//不存在
	
		String localParentPath = getReposRealPath(repos) + doc.getPath();
		File localEntry = new File(localParentPath,doc.getName());
		if(localEntry.exists())
		{
			localDoc.setSize(localEntry.length());
			localDoc.setLatestEditTime(localEntry.lastModified());
			localDoc.setType(localEntry.isDirectory()? 2 : 1);
		}
		return localDoc;
	}
	
	protected boolean verReposPullPush(Repos repos, boolean isRealDoc, ReturnAjax rt)
	{
		Integer isRemote = repos.getIsRemote();
		Integer verCtrl = repos.getVerCtrl();
		if(!isRealDoc)
		{
			verCtrl = repos.getVerCtrl1();
			isRemote = repos.getIsRemote1();
		}
		System.out.println("verReposPullPush() verCtrl:" + verCtrl + " isRemote:" + isRemote);
		
		if(verCtrl != 2 || isRemote != 1)
		{
			System.out.println("verReposPullPush() 非GIT远程仓库无需PullPush");
			return true;
		}
		
		return gitPullPush(repos, isRealDoc);
	}
	
	private boolean gitPullPush(Repos repos, boolean isRealDoc) {
		//GitUtil Init
		GITUtil gitUtil = new GITUtil();
		if(gitUtil.Init(repos, isRealDoc, "") == false)
		{
			System.out.println("gitPull() GITUtil Init failed");
			return false;
		}

		if(gitUtil.doPullEx())
		{
			return gitUtil.doPushEx();
		}
		return false;
	}

	protected String verReposGetLatestRevision(Repos repos, boolean convert, Doc doc) 
	{
		doc = docConvert(doc, convert);
		
		int verCtrl = getVerCtrl(repos, doc);
		if(verCtrl == 1)
		{
			return svnGetDocLatestRevision(repos, doc);			
		}
		else if(verCtrl == 2)
		{
			return gitGetDocLatestRevision(repos, doc);	
		}
		return null;
	}

	private String svnGetDocLatestRevision(Repos repos, Doc doc) {
		SVNUtil svnUtil = new SVNUtil();
		if(svnUtil.Init(repos, doc.getIsRealDoc(), "") == false)
		{
			System.out.println("svnGetDoc() svnUtil.Init失败！");	
			return null;
		}

		return svnUtil.getLatestRevision(doc);		
	}
	
	private String gitGetDocLatestRevision(Repos repos, Doc doc) {
		//GitUtil Init
		GITUtil gitUtil = new GITUtil();
		if(gitUtil.Init(repos, doc.getIsRealDoc(), "") == false)
		{
			System.out.println("gitRealDocCommit() GITUtil Init failed");
			return null;
		}
		
		return gitUtil.getLatestRevision(doc);		
	}


	protected Doc verReposGetDoc(Repos repos, Doc doc, String revision)
	{
		if(repos.getVerCtrl() == 1)
		{
			return svnGetDoc(repos, doc, revision);			
		}
		else if(repos.getVerCtrl() == 2)
		{
			return gitGetDoc(repos, doc, revision);	
		}
		return null;
	}

	private Doc svnGetDoc(Repos repos, Doc doc, String revision) {
		//System.out.println("svnGetDoc() reposId:" + repos.getId() + " parentPath:" + parentPath + " entryName:" + entryName);
		
		SVNUtil svnUtil = new SVNUtil();
		if(svnUtil.Init(repos, true, "") == false)
		{
			System.out.println("svnGetDoc() svnUtil.Init失败！");	
			return null;
		}

		Doc remoteEntry = svnUtil.getDoc(doc, revision);		
		return remoteEntry;
	}

	private Doc gitGetDoc(Repos repos, Doc doc, String revision) 
	{
		//GitUtil Init
		GITUtil gitUtil = new GITUtil();
		if(gitUtil.Init(repos, true, "") == false)
		{
			System.out.println("gitRealDocCommit() GITUtil Init failed");
			return null;
		}
		
		Doc remoteDoc = gitUtil.getDoc(doc, revision);
		return remoteDoc;
	}
	
	protected Doc indexGetDoc(Repos repos, Doc doc, boolean dupCheck) 
	{
		Doc qDoc = new Doc();
		qDoc.setVid(doc.getVid());
		qDoc.setDocId(doc.getDocId());
		
		String indexLib = getIndexLibPath(repos, 0);
		List<Doc> list = LuceneUtil2.getDocList(repos, doc, indexLib);
		if(list == null || list.size() == 0)
		{
			return null;
		}
		
		if(dupCheck)
		{
			if(list.size() > 1)
			{
				System.out.println("indexGetDoc() indexLib存在多个DOC记录(" + doc.getName() + ")，自动清理"); 
				for(int i=0; i <list.size(); i++)
				{
					//delete Doc directly
					LuceneUtil2.deleteDoc(list.get(i), indexLib);
				}
				return null;
			}
		}
		
		return null;
	}

	protected Doc dbGetDoc(Repos repos, Doc doc, boolean dupCheck) 
	{	
		Doc qDoc = new Doc();
		qDoc.setVid(doc.getVid());
		qDoc.setDocId(doc.getDocId());
		
		List<Doc> list = reposService.getDocList(qDoc);
		printObject("dbGetDoc() list:", list);
		
		if(list == null || list.size() == 0)
		{
			return null;
		}
		
		if(dupCheck)
		{
			if(list.size() > 1)
			{
				System.out.println("dbGetDoc() 数据库存在多个DOC记录(" + doc.getName() + ")，自动清理"); 
				for(int i=0; i <list.size(); i++)
				{
					//delete Doc directly
					reposService.deleteDoc(list.get(i).getId());
				}
				return null;
			}
		}
	
		Doc dbDoc = list.get(0);		
		return dbDoc;
	}

	private boolean dbAddDoc(Repos repos, Doc doc, boolean addSubDocs, boolean parentDocCheck) 
	{
		if(repos.getType() != 1)
		{
			return true;
		}
		
		String reposRPath = getReposRealPath(repos);
		String docPath = reposRPath + doc.getPath() + doc.getName();
		File localEntry = new File(docPath);
		if(!localEntry.exists())
		{
			return false;
		}
		doc.setSize(localEntry.length());
		doc.setCreateTime(localEntry.lastModified());
		doc.setLatestEditTime(localEntry.lastModified());
		if(reposService.addDoc(doc) == 0)
		{
			System.out.println("dbAddDoc() addDoc to db failed");		
			return false;
		}
		
		if(addSubDocs)
		{
			List<Doc> subDocList = null;
			if(repos.getType() == 1 || repos.getType() == 2)
			{
				subDocList = getLocalEntryList(repos, doc);	
			}
			else
			{
				subDocList = getRemoteEntryList(repos, doc);	
			}
			
			if(subDocList != null)
			{
				for(int i=0; i<subDocList.size(); i++)
				{
					Doc subDoc = subDocList.get(i);
					subDoc.setCreator(doc.getCreator());
					subDoc.setLatestEditor(doc.getLatestEditor());
					subDoc.setRevision(doc.getRevision());
					dbAddDoc(repos, subDoc, addSubDocs, false);
				}
			}
		}
		return true;
	}
	
	private boolean dbDeleteDoc(Repos repos, Doc doc, boolean deleteSubDocs) 
	{
		if(repos.getType() != 1)
		{
			return true;
		}

		if(deleteSubDocs)
		{
			String subDocParentPath = doc.getPath() + doc.getName() + "/";
			if(doc.getName().isEmpty())
			{
				subDocParentPath = doc.getPath();
			}
			Doc qSubDoc = new Doc();
			qSubDoc.setVid(doc.getVid());
			qSubDoc.setPath(subDocParentPath);
			List<Doc> subDocList = reposService.getDocList(qSubDoc);
			if(subDocList != null)
			{
				for(int i=0; i<subDocList.size(); i++)
				{
					Doc subDoc = subDocList.get(i);
					if(subDoc.getName().isEmpty())
					{
						System.out.println("dbDeleteDoc() 系统错误: subDoc name is empty" + subDoc.getDocId());
						printObject("dbDeleteDoc() doc:", doc);
						printObject("dbDeleteDoc() subDoc:", subDoc);
						continue;
					}
					dbDeleteDoc(repos, subDoc, true);
				}
			}
		}
		
		
		Doc qDoc = new Doc();
		qDoc.setVid(doc.getVid());
		qDoc.setName(doc.getName());
		qDoc.setPath(doc.getPath());
		if(reposService.deleteDoc(qDoc) == 0)
		{
			return false;
		}
		return true;
	}

	//autoDetect: 自动检测是新增还是更新或者非法
	private boolean dbUpdateDoc(Repos repos, Doc doc, boolean autoDetect) 
	{	
		if(repos.getType() != 1)
		{
			return true;
		}

		if(autoDetect == false)
		{
			if(reposService.updateDoc(doc) == 0)
			{
				return false;
			}		
			return true;
		}	
		
		
		Long docId = buildDocId(doc.getPath(), doc.getName());
		if(!doc.getDocId().equals(docId))
		{
			System.out.println("dbUpdateDoc() 非法docId，删除该数据库记录:" + doc.getDocId()  + " " + doc.getPath() + doc.getName());
			dbDeleteDoc(repos, doc, false);
			return true;
		}
		
		Doc localEntry = fsGetDoc(repos, doc);
		if(localEntry == null)
		{
			System.out.println("dbUpdateDoc() get localEntry 异常 for " + doc.getDocId()  + " " + doc.getPath() + doc.getName());
			return false;
		}
		
		if(localEntry.getType() == 0)
		{
			//这次commit是一个删除操作
			System.out.println("dbUpdateDoc() 本地文件/目录删除:" + doc.getDocId() + " " + doc.getPath() + doc.getName()); 
			return dbDeleteDoc(repos, doc, true);
		}
		
		//根据localEntry来设置文件类型
		doc.setType(localEntry.getType());
		
		//dbDoc not exists, do add it
		Doc dbDoc = dbGetDoc(repos, doc, false);
		if(dbDoc == null)
		{
			if(localEntry.getType() != 0)
			{
				System.out.println("dbUpdateDoc() 本地新增文件/目录:" + doc.getDocId() + " " + doc.getPath() + doc.getName()); 
				return dbAddDoc(repos, doc, true, true);
			}
			return true;
		}
		
		//type not matched, do delete it and add it
		if(dbDoc.getType() != localEntry.getType())
		{
			System.out.println("dbUpdateDoc() 本地文件/目录类型改变:" + doc.getDocId() + " " + doc.getPath() + doc.getName()); 
			if(dbDeleteDoc(repos, dbDoc, true) == false)
			{
				System.out.println("dbUpdateDoc() 删除dbDoc失败:" + doc.getDocId() + " " + doc.getPath() + doc.getName()); 
				return false;
			}
			return  dbAddDoc(repos, doc, true, false);	
		}
		
		if(localEntry.getType() == 1 || localEntry.getType() == 2)
		{
			System.out.println("dbUpdateDoc() 本地文件/目录修改:" + doc.getDocId() + " " + doc.getPath() + doc.getName()); 
			//Update the size/lastEditTime/revision for doc
			doc.setId(dbDoc.getId());
			doc.setSize(localEntry.getSize());
			doc.setLatestEditTime(localEntry.getLatestEditTime());
			if(reposService.updateDoc(doc) == 0)
			{
				return false;
			}		
			return true;
		}
		
		System.out.println("dbUpdateDoc() 未知文件类型:" + doc.getType() + doc.getDocId() + " " + doc.getPath() + doc.getName()); 
		return false;
	}

	private static Long buildDocId(String path, String name) 
	{
		int level = getLevelByParentPath(path);
		return buildDocIdByName(level, path, name);
	}

	private boolean dbMoveDoc(Repos repos, Doc srcDoc, Doc dstDoc) 
	{
		if(repos.getType() != 1)
		{
			return true;
		}
		
		dbDeleteDoc(repos, srcDoc,true);
		return dbAddDoc(repos, dstDoc, true, false);
	}
	
	private boolean dbCopyDoc(Repos repos, Doc srcDoc, Doc dstDoc, User login_user, ReturnAjax rt) {
		if(repos.getType() != 1)
		{
			return true;
		}

		return dbAddDoc(repos, dstDoc, true, false);
	}
	
	private boolean dbDeleteDocEx(List<CommonAction> actionList, Repos repos, Doc doc, String commitMsg, String commitUser, boolean deleteSubDocs) 
	{
		if(repos.getType() != 1)
		{
			return true;
		}

		if(deleteSubDocs)
		{
			Doc qSubDoc = new Doc();
			qSubDoc.setVid(doc.getVid());
			qSubDoc.setPath(doc.getPath() + doc.getName() + "/");
			List<Doc> subDocList = reposService.getDocList(qSubDoc);
			if(subDocList != null)
			{
				for(int i=0; i<subDocList.size(); i++)
				{
					Doc subDoc = subDocList.get(i);
					dbDeleteDocEx(actionList, repos, subDoc, commitMsg, commitUser, true);
				}
			}
		}
		
		Doc qDoc = new Doc();
		qDoc.setVid(doc.getVid());
		qDoc.setName(doc.getName());
		qDoc.setPath(doc.getPath());
		if(reposService.deleteDoc(qDoc) == 0)
		{
			return false;
		}
		
		//Build ActionList for RDocIndex/VDoc/VDocIndex/VDocVerRepos delete
		BuildMultiActionListForDocDelete(actionList, repos, doc, commitMsg, commitUser, false);

		return true;
	}

	private boolean executeDBAction(CommonAction action, ReturnAjax rt) 
	{
		printObject("executeDBAction() action:",action);
		Repos repos = action.getRepos();
		Doc doc = action.getDoc();
		System.out.println("executeDBAction() 实文件:" + doc.getDocId() + " " + doc.getPath() + doc.getName());

		switch(action.getAction())
		{
		case ADD:	//Add Doc
			return dbAddDoc(repos, doc, false, true);
		case DELETE: //Delete Doc
			return dbDeleteDoc(repos, doc, true);
		case UPDATE: //Update Doc
			return dbUpdateDoc(repos, doc, true);
		default:
			break;
		}
		return false;
	}
	
	private boolean executeIndexAction(CommonAction action, ReturnAjax rt) 
	{
		printObject("executeIndexAction() action:",action);
		Doc doc = action.getDoc();
		switch(action.getDocType())
		{
		case DOCNAME:	//DocName
			System.out.println("executeIndexAction() 文件名:" + doc.getDocId() + " " + doc.getPath() + doc.getName());
    		return executeIndexActionForDocName(action, rt);
    	case REALDOC: //RDoc
			System.out.println("executeIndexAction() 实文件:" + doc.getDocId() + " " + doc.getPath() + doc.getName());
    		return executeIndexActionForRDoc(action, rt);
		case VIRTURALDOC: //VDoc
			System.out.println("executeIndexAction() 虚文件:" + doc.getDocId() + " " + doc.getPath() + doc.getName());
			return executeIndexActionForVDoc(action, rt);
		default:
			break;
		}
		return false;
	}
	
	private boolean executeIndexActionForDocName(CommonAction action, ReturnAjax rt) 
	{
		Repos repos = action.getRepos();
		Doc doc = action.getDoc();
		
		switch(action.getAction())
		{
		case ADD:	//Add Doc Name
			return addIndexForDocName(repos, doc, rt);
		case DELETE: //Delete Doc Name
			return deleteIndexForDocName(repos, doc, rt);
		case UPDATE: //Update Doc
			Doc newDoc = action.getNewDoc();
			return updateIndexForDocName(repos, doc, newDoc, rt);
		default:
			break;			
		}
		return false;
	}

	private boolean executeIndexActionForRDoc(CommonAction action, ReturnAjax rt) 
	{
		Doc doc = action.getDoc();
		Repos repos = action.getRepos();
		
		switch(action.getAction())
		{
		case ADD:	//Add Doc
			return addIndexForRDoc(repos, doc);
		case DELETE: //Delete Doc
			return deleteIndexForRDoc(repos, doc);
		case UPDATE: //Update Doc
			return updateIndexForRDoc(repos, doc);		
		case MOVE: //Move Doc
			deleteIndexForRDoc(repos, doc);
			return addIndexForRDoc(repos, action.getNewDoc());		
		case COPY: //Copy Doc
			return addIndexForRDoc(repos, action.getNewDoc());
		default:
			break;
		}
		return false;
	}
	
	private boolean executeIndexActionForVDoc(CommonAction action, ReturnAjax rt) 
	{
		Repos repos = action.getRepos();
		Doc doc = action.getDoc();
		
		switch(action.getAction())
		{
		case ADD:	//Add Doc
			return addIndexForVDoc(repos, doc);
		case DELETE: //Delete Doc
			return deleteIndexForVDoc(repos, doc);
		case UPDATE: //Update Doc
			return updateIndexForVDoc(repos, doc);	
		case MOVE: //Move Doc
			deleteIndexForVDoc(repos, doc);
			return addIndexForVDoc(repos, action.getNewDoc());		
		case COPY: //Copy Doc
			return addIndexForVDoc(repos, action.getNewDoc());
		default:
			break;
		}
		return false;
	}
	
	private boolean executeFSAction(CommonAction action, ReturnAjax rt) {
		printObject("executeFSAction() action:",action);
		Doc doc = action.getDoc();
		switch(action.getDocType())
		{
		case REALDOC:	//RDoc
			System.out.println("executeFSAction() 实文件:" + doc.getDocId() + " " + doc.getPath() + doc.getName());
			return executeLocalActionForRDoc(action, rt);
		case VIRTURALDOC: //VDoc
			System.out.println("executeFSAction() 虚文件:" + doc.getDocId() + " " + doc.getPath() + doc.getName());
			return executeLocalActionForVDoc(action, rt);
		default:
			break; 
		}
		return false;
	}
	
	private boolean executeLocalActionForRDoc(CommonAction action, ReturnAjax rt)
	{		
		Doc doc = action.getDoc();
		Doc newDoc = action.getNewDoc();
		
		Repos repos = action.getRepos();
		
		switch(action.getAction())
		{
		case ADD:	//Add Doc
			return createRealDoc(repos, doc, rt);
		case DELETE: //Delete Doc
			return deleteRealDoc(repos, doc, rt);
		case UPDATE: //Update Doc
			MultipartFile uploadFile = action.getUploadFile();
			Integer chunkNum = action.getChunkNum();
			Integer chunkSize = action.getChunkSize();
			String chunkParentPath = action.getChunkParentPath();
			return updateRealDoc(repos, doc, uploadFile, chunkNum, chunkSize, chunkParentPath, rt);
		case MOVE: //Move Doc
			return moveRealDoc(repos, doc, newDoc, rt);
		case COPY: //Copy Doc
			return copyRealDoc(repos, doc, newDoc, rt);
		default:
			break;
		}
		return false;
	}
	
	private boolean executeLocalActionForVDoc(CommonAction action, ReturnAjax rt)
	{	
		Doc doc = action.getDoc();
		Doc newDoc = action.getNewDoc();
		
		Repos repos = action.getRepos();
		
		switch(action.getAction())
		{
		case ADD:	//Add Doc
			return createVirtualDoc(repos, doc, rt);
		case DELETE: //Delete Doc
			return deleteVirtualDoc(repos, doc, rt);
		case UPDATE: //Update Doc
			return saveVirtualDocContent(repos, doc, rt);
		case MOVE: //Move Doc
			return moveVirtualDoc(repos, doc, newDoc, rt);
		case COPY: //Copy Doc
			return copyVirtualDoc(repos, doc, newDoc, rt);
		default:
			break;
		}
		return false;
	}

	private String executeVerReposAction(CommonAction action, ReturnAjax rt) 
	{
		printObject("executeVerReposAction() action:",action);
		Repos repos = action.getRepos();
		Doc doc = action.getDoc();
		
		Doc inputDoc = doc;
		Doc inputDstDoc = action.getNewDoc();

		boolean isRealDoc = true;
		if(action.getDocType() == DocType.VIRTURALDOC)
		{
			isRealDoc = false;
			inputDoc = buildVDoc(doc);
			
			if(inputDstDoc != null)
			{
				inputDstDoc = buildVDoc(action.getNewDoc());
			}
		}
		
		String ret;
		switch(action.getAction())
		{
		case ADD: //add
		case DELETE:	//delete
		case UPDATE: //update
			ret = verReposDocCommit(repos, false, inputDoc, action.getCommitMsg(), action.getCommitUser(), rt, true, null, 2, null);
			verReposPullPush(repos, isRealDoc, rt);
			return ret;
		case MOVE:	//move
			ret = verReposDocMove(repos, false, inputDoc, inputDstDoc, action.getCommitMsg(), action.getCommitUser(), rt, null);
			verReposPullPush(repos, isRealDoc, rt);
			return ret;
		case COPY: //copy
			ret = verReposDocCopy(repos, false, inputDoc, inputDstDoc, action.getCommitMsg(), action.getCommitUser(), rt, null);
			verReposPullPush(repos, isRealDoc, rt);
			return ret;
		case PUSH: //pull
			if(verReposPullPush(repos, isRealDoc, rt) == false)
			{
				return null;
			}
			return "PUSHOK";
		default:
			break;				
		}
		return null;
	}

	//底层updateDoc接口
	protected boolean updateDoc(Repos repos, Doc doc,
								MultipartFile uploadFile,
								Integer chunkNum, Integer chunkSize, String chunkParentPath, 
								String commitMsg,String commitUser,User login_user, ReturnAjax rt, List<CommonAction> actionList) 
	{
		switch(repos.getType())
		{
		case 1:
		case 2:
		case 3:
		case 4:
			return updateDoc_FSM(repos, doc,
					uploadFile,
					chunkNum, chunkSize, chunkParentPath, 
					commitMsg, commitUser, login_user, rt, actionList);
		}
		return false;
	}

	protected boolean updateDoc_FSM(Repos repos, Doc doc,
				MultipartFile uploadFile,
				Integer chunkNum, Integer chunkSize, String chunkParentPath, 
				String commitMsg,String commitUser,User login_user, ReturnAjax rt,
				List<CommonAction> actionList) 
	{	
		DocLock docLock = null;
		synchronized(syncLock)
		{
			//Try to lock the doc
			docLock = lockDoc(doc, 1, 2*60*60*1000, login_user, rt,false); //lock 2 Hours 2*60*60*1000
			if(docLock == null)
			{
				unlock(); //线程锁
	
				System.out.println("updateDoc() lockDoc " + doc.getName() +" Failed！");
				return false;
			}
			unlock(); //线程锁
		}

		//get RealDoc Full ParentPath
		String reposRPath =  getReposRealPath(repos);		

		//保存文件信息
		if(updateRealDoc(repos, doc, uploadFile,chunkNum,chunkSize,chunkParentPath,rt) == false)
		{
			unlockDoc(doc,login_user,docLock);

			System.out.println("updateDoc() saveFile " + doc.getName() +" Failed, unlockDoc Ok");
			rt.setError("Failed to updateRealDoc " + doc.getName());
			return false;
		}
		
		doc.setLatestEditor(login_user.getId());
		doc.setLatestEditorName(login_user.getName());
		
		//Get latestEditTime
		Doc fsDoc = fsGetDoc(repos, doc);
		doc.setLatestEditTime(fsDoc.getLatestEditTime());

		//需要将文件Commit到版本仓库上去
		String revision = verReposDocCommit(repos, false, doc, commitMsg,commitUser,rt, true, null, 2, null);
		if(revision == null)
		{
			docSysDebugLog("updateDoc() verReposRealDocCommit Failed:" + doc.getPath() + doc.getName(), rt);
			docSysWarningLog("verReposRealDocCommit Failed", rt);	
		}
		else
		{
			//updateDoc Info
			doc.setRevision(revision);
			if(dbUpdateDoc(repos, doc, true) == false)
			{
				docSysWarningLog("updateDoc() updateDocInfo Failed", rt);
			}
			dbCheckAddUpdateParentDoc(repos, doc, null);
			//Insert Push Action
			insertCommonAction(actionList, repos, doc, null, commitMsg, commitUser, ActionType.VERREPOS, Action.PUSH, DocType.REALDOC, null, login_user);
		}
		
		//Build DocUpdate action
		BuildMultiActionListForDocUpdate(actionList, repos, doc, reposRPath);
		
		unlockDoc(doc,login_user,docLock);
		
		return true;
	}
	
	protected boolean renameDoc(Repos repos, Doc srcDoc, Doc dstDoc, String commitMsg, String commitUser, User login_user, ReturnAjax rt, List<CommonAction> actionList) {
		switch(repos.getType())
		{
		case 1:
		case 2:
		case 3:
		case 4:
			return 	moveDoc_FSM(repos, srcDoc, dstDoc, commitMsg, commitUser, login_user, rt, actionList);
		}
		return false;
	}
	

	protected boolean moveDoc(Repos repos, Doc srcDoc, Doc dstDoc, String commitMsg, String commitUser, User login_user, ReturnAjax rt, List<CommonAction> actionList) {
		switch(repos.getType())
		{
		case 1:
		case 2:
		case 3:
		case 4:
			return 	moveDoc_FSM(repos, srcDoc, dstDoc, commitMsg, commitUser, login_user, rt, actionList);
		}
		return false;
	}

	private boolean moveDoc_FSM(Repos repos, Doc srcDoc, Doc dstDoc, String commitMsg, String commitUser, User login_user,
			ReturnAjax rt, List<CommonAction> actionList) 
	{
		DocLock srcDocLock = null;
		DocLock dstDocLock = null;
		synchronized(syncLock)
		{
			//Try to lock the srcDoc
			srcDocLock = lockDoc(srcDoc,1, 2*60*60*1000,login_user,rt,true);
			if(srcDocLock == null)
			{
				unlock(); //线程锁
		
				docSysErrorLog("moveDoc_FSM() lock srcDoc " + srcDoc.getName() + " Failed", rt);
				return false;
			}
			
			dstDocLock = lockDoc(dstDoc,1, 2*60*60*1000,login_user,rt,true);
			if(dstDocLock == null)
			{
				unlock(); //线程锁
				docSysErrorLog("moveDoc_FSM() lock dstDoc " + dstDoc.getName() + " Failed", rt);

				unlockDoc(srcDoc, login_user, srcDocLock);
				return false;
			}
			
			unlock(); //线程锁
		}
		
		if(moveRealDoc(repos, srcDoc, dstDoc, rt) == false)
		{
			unlockDoc(srcDoc, login_user, srcDocLock);
			unlockDoc(dstDoc, login_user, dstDocLock);

			docSysErrorLog("moveDoc_FSM() moveRealDoc " + srcDoc.getName() + " to " + dstDoc.getName() + " 失败", rt);
			return false;
		}
		
		String revision = verReposDocMove(repos, true, srcDoc, dstDoc,commitMsg, commitUser,rt, null);
		if(revision == null)
		{
			docSysWarningLog("moveDoc_FSM() verReposRealDocMove Failed", rt);
		}
		else
		{
			dstDoc.setRevision(revision);
			if(dbMoveDoc(repos, srcDoc, dstDoc) == false)
			{
				docSysWarningLog("moveDoc_FSM() dbMoveDoc failed", rt);			
			}
			dbCheckAddUpdateParentDoc(repos, dstDoc, null);
		}
		
		//Build Async Actions For RealDocIndex\VDoc\VDocIndex Add
		BuildMultiActionListForDocCopy(actionList, repos, srcDoc, dstDoc, commitMsg, commitUser, true);
		
		unlockDoc(srcDoc,login_user,srcDocLock);
		unlockDoc(dstDoc,login_user,dstDocLock);
		
		Doc fsDoc = fsGetDoc(repos, dstDoc);
		dstDoc.setLatestEditTime(fsDoc.getLatestEditTime());
		
		rt.setData(dstDoc);
		return true;
	}
	
	//底层copyDoc接口
	protected boolean copyDoc(Repos repos, Doc srcDoc, Doc dstDoc, 
			String commitMsg,String commitUser,User login_user, ReturnAjax rt,List<CommonAction> actionList) 
	{
		switch(repos.getType())
		{
		case 1:
		case 2:
		case 3:
		case 4:
			return 	copyDoc_FSM(repos, srcDoc, dstDoc,
					commitMsg, commitUser, login_user, rt, actionList);
		}
		return false;
	}

	protected boolean copyDoc_FSM(Repos repos, Doc srcDoc, Doc dstDoc,
			String commitMsg,String commitUser,User login_user, ReturnAjax rt, List<CommonAction> actionList)
	{				
		DocLock srcDocLock = null;
		DocLock dstDocLock = null;
		synchronized(syncLock)
		{
			//Try to lock the srcDoc
			srcDocLock = lockDoc(srcDoc,1, 2*60*60*1000,login_user,rt,true);
			if(srcDocLock == null)
			{
				unlock(); //线程锁
		
				System.out.println("copyDoc lock srcDoc " + srcDoc.getName() + " Failed");
				return false;
			}
			
			dstDocLock = lockDoc(dstDoc,1, 2*60*60*1000,login_user,rt,true);
			if(dstDocLock == null)
			{
				unlock(); //线程锁
				System.out.println("copyDoc lock dstcDoc " + dstDoc.getName() + " Failed");
				
				unlockDoc(srcDoc, login_user, srcDocLock);
				
				return false;
			}
			
			unlock(); //线程锁
		}
						
		//复制文件或目录
		if(copyRealDoc(repos, srcDoc, dstDoc, rt) == false)
		{
			unlockDoc(srcDoc,login_user,null);
			unlockDoc(dstDoc,login_user,null);

			System.out.println("copy " + srcDoc.getName() + " to " + dstDoc.getName() + " 失败");
			rt.setError("copyRealDoc copy " + srcDoc.getName() + " to " + dstDoc.getName() + "Failed");
			return false;
		}
			
		//需要将文件Commit到VerRepos上去
		String revision = verReposDocCopy(repos, true, srcDoc, dstDoc,commitMsg, commitUser,rt, null);
		if(revision == null)
		{
			docSysWarningLog("copyDoc() verReposRealDocCopy failed", rt);
		}
		else
		{
			dstDoc.setRevision(revision);
			if(dbCopyDoc(repos, srcDoc, dstDoc, login_user, rt) == false)
			{
				docSysWarningLog("copyDoc() dbCopyDoc failed", rt);			
			}
			dbCheckAddUpdateParentDoc(repos, dstDoc, null);
		}
		
		//Build Async Actions For RealDocIndex\VDoc\VDocIndex Add
		BuildMultiActionListForDocCopy(actionList, repos, srcDoc, dstDoc, commitMsg, commitUser, false);
		
		unlockDoc(srcDoc,login_user,srcDocLock);
		unlockDoc(dstDoc,login_user,dstDocLock);
		
		//只返回最上层的doc记录
		rt.setData(dstDoc);
		return true;
	}

	protected boolean updateRealDocContent(Repos repos, Doc doc, 
			String commitMsg, String commitUser, User login_user,ReturnAjax rt, List<CommonAction> actionList) 
	{		
		DocLock docLock = null;
		synchronized(syncLock)
		{
			//Try to lock Doc
			docLock = lockDoc(doc,2, 1*60*60*1000, login_user,rt,false);
			if(docLock == null)
			{
				unlock(); //线程锁
	
				System.out.println("updateDocContent() lockDoc Failed");
				return false;
			}
			unlock(); //线程锁
		}
		
		boolean ret = updateRealDocContent_FSM(repos, doc, commitMsg, commitUser, login_user, rt, actionList);
		
		//revert the lockStatus
		unlockDoc(doc, login_user, docLock);
				
		return ret;
	}
	
	private boolean updateRealDocContent_FSM(Repos repos, Doc doc,
			String commitMsg, String commitUser, User login_user, ReturnAjax rt, List<CommonAction> actionList) 
	{
		if(saveRealDocContent(repos, doc, rt) == true)
		{
			verReposDocCommit(repos, false, doc, commitMsg, commitUser,rt, true, null, 2, null);

			//Insert Push Action
			insertCommonAction(actionList, repos, doc, null, commitMsg, commitUser, ActionType.VERREPOS, Action.PUSH, DocType.REALDOC, null, login_user);

			//Insert index add action for VDoc
			insertCommonAction(actionList, repos, doc, null, commitMsg, commitUser, ActionType.INDEX, Action.UPDATE, DocType.REALDOC, null, login_user);
			return true;
		}
		return false;
	}
	
	protected boolean updateVirualDocContent(Repos repos, Doc doc, 
			String commitMsg, String commitUser, User login_user,ReturnAjax rt, List<CommonAction> actionList) 
	{		
		DocLock docLock = null;
		synchronized(syncLock)
		{
			//Try to lock Doc
			docLock = lockDoc(doc,2, 1*60*60*1000, login_user,rt,false);
			if(docLock == null)
			{
				unlock(); //线程锁
	
				System.out.println("updateDocContent() lockDoc Failed");
				return false;
			}
			unlock(); //线程锁
		}
		
		boolean ret = updateVirualDocContent_FSM(repos, doc, commitMsg, commitUser, login_user, rt, actionList);
		
		//revert the lockStatus
		unlockDoc(doc, login_user, docLock);
				
		return ret;
	}

	private boolean updateVirualDocContent_FSM(Repos repos, Doc doc,
			String commitMsg, String commitUser, User login_user, ReturnAjax rt, List<CommonAction> actionList) 
	{
		//Save the content to virtual file
		if(isVDocExist(repos, doc) == true)
		{
			if(saveVirtualDocContent(repos, doc, rt) == true)
			{
				Doc vDoc = buildVDoc(doc);
				verReposDocCommit(repos, false, vDoc, commitMsg, commitUser,rt, true, null, 2, null);

				//Insert Push Action
				insertCommonAction(actionList, repos, doc, null, commitMsg, commitUser, ActionType.VERREPOS, Action.PUSH, DocType.VIRTURALDOC, null, login_user);

				//Insert index add action for VDoc
				insertCommonAction(actionList, repos, doc, null, commitMsg, commitUser, ActionType.INDEX, Action.UPDATE, DocType.VIRTURALDOC, null, login_user);
				return true;
			}
		}
		else
		{	
			//创建虚拟文件目录：用户编辑保存时再考虑创建
			if(createVirtualDoc(repos, doc, rt) == true)
			{
				Doc vDoc = buildVDoc(doc);
				verReposDocCommit(repos, false, vDoc, commitMsg, commitUser,rt, true, null, 2, null);

				//Insert Push Action
				insertCommonAction(actionList, repos, doc, null, commitMsg, commitUser, ActionType.VERREPOS, Action.PUSH, DocType.VIRTURALDOC, null, login_user);

				//Insert index update action for VDoc
				insertCommonAction(actionList, repos, doc, null, commitMsg, commitUser, ActionType.INDEX, Action.ADD, DocType.VIRTURALDOC, null, login_user);
				return true;
			}
		}
				
		return false;
	}
	
	/************************ DocSys仓库与文件锁定接口 *******************************/
	//Lock Repos
	protected Repos lockRepos(Integer reposId,Integer lockType, long lockDuration, User login_user, ReturnAjax rt, boolean docLockCheckFlag) 
	{
		System.out.println("lockRepos() reposId:" + reposId + " lockType:" + lockType + " by " + login_user.getName() + " docLockCheckFlag:" + docLockCheckFlag);
		//确定Repos是否可用
		Repos repos = reposService.getRepos(reposId);
		if(repos == null)
		{
			rt.setError("repos " + reposId +" 不存在！");
			System.out.println("lockRepos() Repos: " + reposId +" 不存在！");
			return null;
		}
		
		//Check if repos was locked
		if(isReposLocked(repos, login_user,rt))
		{
			System.out.println("lockRepos() Repos:" + repos.getId() +" was locked！");				
			return null;			
		}
		
		if(docLockCheckFlag)
		{
			Doc doc = new Doc();
			doc.setVid(reposId);
			doc.setDocId((long) 0);
			if(isSubDocLocked(doc,login_user, rt) == true)
			{
				System.out.println("lockRepos() doc was locked！");
				return null;
			}
		}
		
		//lockTime is the time to release lock 
		Repos lockRepos= new Repos();
		lockRepos.setId(reposId);
		lockRepos.setState(lockType);
		lockRepos.setLockBy(login_user.getId());
		long lockTime = new Date().getTime() + lockDuration; //24*60*60*1000;
		lockRepos.setLockTime(lockTime);	//Set lockTime
		if(reposService.updateRepos(lockRepos) == 0)
		{
			rt.setError("lock Repos:" + reposId +"[" + repos.getName() +"]  failed");
			return null;
		}
		System.out.println("lockRepos() success reposId:" + reposId + " lockType:" + lockType + " by " + login_user.getName());
		return repos;	
	}
	

	//确定仓库是否被锁定
	private boolean isReposLocked(Repos repos, User login_user, ReturnAjax rt) {
		int lockState = repos.getState();	//0: not locked  1: locked	
		if(lockState != 0)
		{
			if(isLockOutOfDate(repos.getLockTime()) == false)
			{	
				User lockBy = getLocker(repos.getLockBy());
				String lockTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(repos.getLockTime());
				
				rt.setError("仓库 " + repos.getName() +" was locked by [" + repos.getLockBy() + "] "+ lockBy.getName() + " till " + lockTime);
				System.out.println("Repos " + repos.getId()+ "[" + repos.getName() +"] was locked by " + repos.getLockBy() + " lockState:"+ repos.getState());;
				return true;						
			}
			else 
			{
				System.out.println("Repos " + repos.getId()+ " " + repos.getName()  +" lock was out of date！");
				return false;
			}
		}
		return false;
	}
	
	//Unlock Doc
	protected boolean unlockRepos(Integer reposId, User login_user, Repos preLockInfo) {
		Repos curRepos = reposService.getRepos(reposId);
		if(curRepos == null)
		{
			System.out.println("unlockRepos() curRepos is null " + reposId);
			return false;
		}
		
		if(curRepos.getState() == 0)
		{
			System.out.println("unlockRepos() repos was not locked:" + curRepos.getState());			
			return true;
		}
		
		Integer lockBy = curRepos.getLockBy();
		if(lockBy != null && lockBy == login_user.getId())
		{
			Repos revertRepos = new Repos();
			revertRepos.setId(reposId);	
			
			if(preLockInfo == null)	//Unlock
			{
				revertRepos.setState(0);	//
				revertRepos.setLockBy(0);	//
				revertRepos.setLockTime((long)0);	//Set lockTime
			}
			else	//Revert to preLockState
			{
				revertRepos.setState(preLockInfo.getState());	//
				revertRepos.setLockBy(preLockInfo.getLockBy());	//
				revertRepos.setLockTime(preLockInfo.getLockTime());	//Set lockTime
			}
			
			if(reposService.updateRepos(revertRepos) == 0)
			{
				System.out.println("unlockRepos() updateRepos Failed!");
				return false;
			}
		}
		else
		{
			System.out.println("unlockRepos() repos was not locked by " + login_user.getName());
			return false;
		}
		
		System.out.println("unlockRepos() success:" + reposId);
		return true;
	}
	
	//Lock Doc
	protected DocLock lockDoc(Doc doc,Integer lockType, long lockDuration, User login_user, ReturnAjax rt, boolean subDocCheckFlag) {
		System.out.println("lockDoc() doc:" + doc.getName() + " lockType:" + lockType + " login_user:" + login_user.getName() + " subDocCheckFlag:" + subDocCheckFlag);

		if(doc.getType() == null)
		{
			System.out.println("lockDoc() Doc type is null for " + doc.getDocId() + " " + doc.getPath() + doc.getName() );				
		}
		
		//check if the doc was locked (State!=0 && lockTime - curTime > 1 day)
		DocLock docLock = getDocLock(doc);
		if(docLock != null && isDocLocked(docLock,login_user,rt))
		{
			System.out.println("lockDoc() Doc " + doc.getName() +" was locked");
			return null;
		}
		
		//检查其父节点是否强制锁定
		if(isParentDocLocked(doc,login_user,rt))
		{
			System.out.println("lockDoc() Parent Doc of " + doc.getName() +" was locked！");				
			return null;
		}
		
		//Check If SubDoc was locked
		if(subDocCheckFlag)
		{
			if(isSubDocLocked(doc,login_user, rt) == true)
			{
				System.out.println("lockDoc() subDoc of " + doc.getName() +" was locked！");
				return null;
			}
		}
		
		//Do Lock
		//lockTime is the time to release lock 
		long lockTime = new Date().getTime() + lockDuration;
		
		if(docLock == null)
		{
			docLock = new DocLock();
			docLock.setVid(doc.getVid());
			docLock.setPid(doc.getPid());			
			docLock.setDocId(doc.getDocId());
			docLock.setPath(doc.getPath());			
			docLock.setName(doc.getName());			
			docLock.setType(doc.getType());
			
			docLock.setState(lockType);	//doc的状态为不可用
			docLock.setLocker(login_user.getName());
			docLock.setLockBy(login_user.getId());
			docLock.setLockTime(lockTime);	//Set lockTime
			if(reposService.addDocLock(docLock) == 0)
			{
				rt.setError("lock Doc [" + doc.getName() +"]  failed");
				return null;
			}
			
			//Set LockState = 0, which will be used for unlockDoc
			docLock.setState(0);
		}
		else
		{
			DocLock newDocLock = new DocLock();
			newDocLock.setId(docLock.getId());
			newDocLock.setState(lockType);	//doc的状态为不可用
			newDocLock.setLocker(login_user.getName());
			newDocLock.setLockBy(login_user.getId());
			newDocLock.setLockTime(lockTime);	//Set lockTime
			if(reposService.updateDocLock(newDocLock) == 0)
			{
				rt.setError("lock Doc [" + doc.getName() +"]  failed");
				return null;
			}
		}
		
		System.out.println("lockDoc() " + doc.getName() + " success lockType:" + lockType + " by " + login_user.getName());
		return docLock;
	}
	
	private DocLock getDocLock(Doc doc) {
		DocLock qDocLock = new DocLock();
		qDocLock.setVid(doc.getVid());
		qDocLock.setPath(doc.getPath());
		qDocLock.setName(doc.getName());
		
		List<DocLock> list = reposService.getDocLockList(qDocLock);
		if(list == null || list.size() == 0)
		{
			return null;
		}
		
		return list.get(0);
	}

	private User getLocker(Integer userId) {
		User user = new User();
		if(userId == 0)	//AutoSync
		{
			user.setId(0);
			user.setName("AutoSync");
			return user;
		}

		user = userService.getUser(userId);
		return user;
	}

	//确定当前doc是否被锁定
	private boolean isDocLocked(DocLock docLock,User login_user,ReturnAjax rt) {
		if(docLock == null)
		{
			return false;
		}
		
		int lockState = docLock.getState();	//0: not locked 2: 表示强制锁定（实文件正在新增、更新、删除），不允许被自己解锁；1: 表示RDoc处于CheckOut 3:表示正在编辑VDoc
		if(lockState != 0)
		{
			//Not force locked (user can access it by himself)
			if(lockState != 2)
			{
				if(docLock.getLockBy() == login_user.getId())	//locked by login_user
				{
					System.out.println("Doc: " + docLock.getName() +" was locked by user:" + docLock.getLockBy() +" login_user:" + login_user.getId());
					return false;
				}
			}
			
			if(isLockOutOfDate(docLock.getLockTime()) == false)
			{	
				String lockTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(docLock.getLockTime());

				rt.setError(docLock.getName() +" was locked by [" + docLock.getLockBy() + "] " +docLock.getLocker() + " till " + lockTime);
				
				System.out.println("Doc [" + docLock.getName() +"] was locked by " + docLock.getLocker() + " lockState:"+ docLock.getState());
				return true;						
			}
			else 
			{
				System.out.println("doc " + docLock.getId()+ " " + docLock.getName()  +" lock was out of date！");
				return false;
			}
		}
		return false;
	}

	private boolean isLockOutOfDate(long lockTime) {
		//check if the lock was out of date
		long curTime = new Date().getTime();
		//System.out.println("isLockOutOfDate() curTime:"+curTime+" lockTime:"+lockTime);
		if(curTime < lockTime)	//
		{
			return false;
		}

		//Lock 自动失效
		return true;
	}

	//确定parentDoc is Force Locked
	private boolean isParentDocLocked(Doc doc, User login_user,ReturnAjax rt) 
	{
		//Check if the rootDoc locked
		Integer reposId = doc.getVid();
		Doc tempDoc = new Doc();
		tempDoc.setVid(reposId);
		tempDoc.setPath("");
		tempDoc.setName("");
		DocLock lock = getDocLock(doc);
		if(isDocLocked(lock, login_user, rt))
		{
			return true;
		}
		
		//Check parentDoc locked
		String parentPath = doc.getPath();
		if(parentPath == null || parentPath.isEmpty())
		{
			return false;
		}
				
		String [] paths = parentPath.split("/");

		String path = "";		
		for(int i=0; i< paths.length; i++)
		{
			String name = paths[i];
			if(name.isEmpty())
			{
				continue;
			}
			
			tempDoc.setPath(path);
			tempDoc.setName(name);
			lock = getDocLock(doc);
			if(isDocLocked(lock, login_user, rt))
			{
				return true;
			}
			path = path + name +"/";
		}
		return false;
	}
	
	//docId目录下是否有锁定的doc(包括所有锁定状态)
	//Check if any subDoc under docId was locked, you need to check it when you want to rename/move/copy/delete the Directory
	private boolean isSubDocLocked(Doc doc, User login_user, ReturnAjax rt)
	{
		Integer reposId = doc.getVid();
		
		//Set the query condition to get the SubDocList of DocId
		DocLock qDocLock = new DocLock();
		qDocLock.setVid(doc.getVid());
		qDocLock.setPath(doc.getPath() + doc.getName() + "/");
		List<DocLock> SubDocLockList = reposService.getDocLockList(qDocLock);

		for(int i=0;i<SubDocLockList.size();i++)
		{
			DocLock subDocLock =SubDocLockList.get(i);
			if(isDocLocked(subDocLock, login_user, rt))
			{
				rt.setError("subDoc [" +  subDocLock.getName() + "] is locked:" + subDocLock.getState());
				System.out.println("isSubDocLocked() " + subDocLock.getName() + " is locked!");
				return true;
			}
			
			//If SubDocLock is for directory or unknown type, need to check its subDocLocks
			if(subDocLock.getType() == null || subDocLock.getType() == 2)
			{
				Doc subDoc = new Doc();
				subDoc.setVid(reposId);
				subDoc.setPath(subDocLock.getPath());
				subDoc.setName(subDocLock.getName());
				if(isSubDocLocked(subDoc, login_user, rt))
				{
					return true;
				}
			}
		}
		return false;
	}
	
	//Unlock Doc
	protected boolean unlockDoc(Doc doc, User login_user, DocLock preDocLock) 
	{
		DocLock curDocLock = getDocLock(doc);
		if(curDocLock == null)
		{
			System.out.println("unlockDoc() curDocLock is null ");
			return true;
		}
		
		if(curDocLock.getState() == 0)
		{
			System.out.println("unlockDoc() doc was not locked:" + curDocLock.getState());			
			return true;
		}
		
		if(preDocLock != null && preDocLock.getState() != 0)	//Revert to preDocLock
		{
			if(reposService.updateDocLock(preDocLock) == 0)
			{
				System.out.println("unlockDoc() updateDocLock Failed!");
				return false;
			}
		}
		
		if(reposService.deleteDocLock(curDocLock) == 0)
		{
			System.out.println("unlockDoc() deleteDocLock Failed!");
			return false;
		}
		
		System.out.println("unlockDoc() success:" + doc.getName());
		return true;
	}	
	/********************* DocSys权限相关接口 ****************************/
	//检查用户的新增权限
	protected boolean checkUserAddRight(Repos repos, Integer userId, Doc doc, ReturnAjax rt) 
	{		
		DocAuth docUserAuth = getUserDocAuth(repos, userId, doc);
		if(docUserAuth == null)
		{
			rt.setError("您无此操作权限，请联系管理员");
			return false;
		}
		else
		{
			if(docUserAuth.getAccess() == 0)
			{
				rt.setError("您无权访问该目录，请联系管理员");
				return false;
			}
			else if(docUserAuth.getAddEn() == null || docUserAuth.getAddEn() != 1)
			{
				rt.setError("您没有该目录的新增权限，请联系管理员");
				return false;				
			}
		}
		return true;
	}

	protected boolean checkUserDeleteRight(Repos repos, Integer userId, Doc doc, ReturnAjax rt)
	{	
		DocAuth docUserAuth = getUserDocAuth(repos, userId, doc);
		if(docUserAuth == null)
		{
			rt.setError("您无此操作权限，请联系管理员");
			return false;
		}
		else
		{
			if(docUserAuth.getAccess() == 0)
			{
				rt.setError("您无权访问该目录，请联系管理员");
				return false;
			}
			else if(docUserAuth.getDeleteEn() == null || docUserAuth.getDeleteEn() != 1)
			{
				rt.setError("您没有该目录的删除权限，请联系管理员");
				return false;				
			}
		}
		return true;
	}
	
	protected boolean checkUserEditRight(Repos repos, Integer userId, Doc doc, ReturnAjax rt)
	{
		DocAuth docUserAuth = getUserDocAuth(repos, userId, doc);
		if(docUserAuth == null)
		{
			rt.setError("您无此操作权限，请联系管理员");
			return false;
		}
		else
		{
			if(docUserAuth.getAccess() == 0)
			{
				rt.setError("您无权访问该文件，请联系管理员");
				return false;
			}
			else if(docUserAuth.getEditEn() == null || docUserAuth.getEditEn() != 1)
			{
				rt.setError("您没有该文件的编辑权限，请联系管理员");
				return false;				
			}
		}
		return true;
	}
	
	protected boolean checkUseAccessRight(Repos repos, Integer userId, Doc doc, ReturnAjax rt)
	{
		DocAuth docAuth = getUserDocAuth(repos, userId, doc);
		if(docAuth == null)
		{
			rt.setError("您无此操作权限，请联系管理员");
			return false;
		}
		else
		{
			Integer access = docAuth.getAccess();
			if(access == null || access.equals(0))
			{
				rt.setError("您无权访问该文件，请联系管理员");
				return false;
			}
		}
		return true;
	}
	
	protected String getUserName(Integer userId) {
		if(userId == null)
		{
			return "";
		}	
		else if(userId == 0)
		{
			return "任意用户";
		}
		else
		{
			//GetUserInfo
			User user = reposService.getUserInfo(userId);
			if(user == null)
			{
				System.out.println("getUserName() user:" +userId+ "not exists");
				return null;
			}
			return user.getName();
		}
	}

	
	private String getGroupName(Integer groupId) {
		UserGroup group = reposService.getGroupInfo(groupId);
		if(group == null)
		{
			System.out.println("getGroupName() Group:" +groupId+ "not exists");
			return null;
		}
		return group.getName();
	}
	

	//获取用户的仓库权限设置
	private HashMap<Integer, ReposAuth> getUserReposAuthHashMap(Integer userId) {
		ReposAuth qReposAuth = new ReposAuth();
		qReposAuth.setUserId(userId);
		List <ReposAuth> reposAuthList = reposService.getReposAuthListForUser(qReposAuth);
		printObject("getUserReposAuthHashMap() userID[" + userId +"] reposAuthList:", reposAuthList);
		
		if(reposAuthList == null || reposAuthList.size() == 0)
		{
			return null;
		}
		
		HashMap<Integer,ReposAuth> hashMap = BuildHashMapByReposAuthList(reposAuthList);
		return hashMap;
	}
	
	protected boolean isAdminOfDoc(Repos repos, User login_user, Doc doc) 
	{
		if(login_user.getType() == 2)	//超级管理员可以访问所有目录
		{
			System.out.println("超级管理员");
			return true;
		}
		
		DocAuth userDocAuth = getUserDocAuth(repos, login_user.getId(), doc);
		if(userDocAuth != null && userDocAuth.getIsAdmin() != null && userDocAuth.getIsAdmin() == 1)
		{
			return true;
		}
		return false;
	}
	
	protected boolean isAdminOfRepos(User login_user,Integer reposId) {
		if(login_user.getType() == 2)	//超级管理员可以访问所有目录
		{
			System.out.println("超级管理员");
			return true;
		}
		
		ReposAuth reposAuth = getUserReposAuth(login_user.getId(),reposId);
		if(reposAuth != null && reposAuth.getIsAdmin() != null && reposAuth.getIsAdmin() == 1)
		{
			return true;
		}			
		return false;
	}
	
	//获取用户真正的仓库权限(已考虑了所在组以及任意用户权限)
	public ReposAuth getUserDispReposAuth(Integer UserID,Integer ReposID)
	{
		ReposAuth reposAuth = getUserReposAuth(UserID,ReposID);
		
		String userName = getUserName(UserID);
		if(reposAuth!=null)
		{
			reposAuth.setUserName(userName);
		}
		else
		{
			reposAuth = new ReposAuth();
			//reposAuth.setUserId(UserID);
			reposAuth.setUserName(userName);
			//reposAuth.setReposId(ReposID);
			//reposAuth.setIsAdmin(0);
			//reposAuth.setAccess(0);
			//reposAuth.setEditEn(0);
			//reposAuth.setAddEn(0);
			//reposAuth.setDeleteEn(0);
			//reposAuth.setHeritable(0);	
		}
		return reposAuth;
	}
	
	public ReposAuth getUserReposAuth(Integer UserID,Integer ReposID)
	{
		System.out.println("getUserReposAuth() UserID:"+UserID);
		ReposAuth qReposAuth = new ReposAuth();
		qReposAuth.setUserId(UserID);
		qReposAuth.setReposId(ReposID);
		List<ReposAuth> reposAuthList = reposService.getReposAuthListForUser(qReposAuth);
		if(reposAuthList == null || reposAuthList.size() == 0)
		{
			return null;
		}
		
		//reposAuth Init
		ReposAuth reposAuth = reposAuthList.get(0);
		Integer oldPriority = reposAuth.getPriority();
		for(int i=1;i<reposAuthList.size();i++){
			//Find the reposAuth with highest priority
			ReposAuth tmpReposAuth = reposAuthList.get(i);
			Integer newPriority = tmpReposAuth.getPriority();
			if(newPriority > oldPriority)
			{
				reposAuth = tmpReposAuth;
			}
			else if(newPriority == oldPriority)
			{
				xorReposAuth(reposAuth,tmpReposAuth);
			}
		}
		return reposAuth;
	}
	
	
	//应该考虑将获取Group、User的合并到一起
	protected DocAuth getGroupDispDocAuth(Repos repos, Integer groupId, Doc doc) 
	{
		System.out.println("getGroupDispDocAuth() groupId:"+groupId);
		
		DocAuth docAuth = getGroupDocAuth(repos, groupId, doc);	//获取用户真实的权限
		
		 String groupName = getGroupName(groupId);
		 
		 //转换成可显示的权限
		if(docAuth == null)
		{
			docAuth = new DocAuth();
			docAuth.setGroupId(groupId);
			docAuth.setGroupName(groupName);
			docAuth.setDocId(doc.getDocId());
			docAuth.setDocName(doc.getName());
			docAuth.setDocPath(doc.getPath());
			docAuth.setReposId(repos.getId());
		}
		else	//如果docAuth非空，需要判断是否是直接权限，如果不是需要对docAuth进行修改
		{
			if(docAuth.getUserId() != null || !docAuth.getGroupId().equals(groupId) || !docAuth.getDocId().equals(doc.getDocId()))
			{
				System.out.println("getGroupDispDocAuth() docAuth为继承的权限,需要删除reposAuthId并设置groupId、groupName");
				docAuth.setId(null);	//clear reposAuthID, so that we know this setting was not on user directly
			}
			//修改信息
			docAuth.setGroupId(groupId);
			docAuth.setGroupName(groupName);
			docAuth.setDocId(doc.getDocId());
			docAuth.setDocName(doc.getName());
			docAuth.setDocPath(doc.getPath());
			docAuth.setReposId(repos.getId());
		}
		return docAuth;
	}
	
	//获取用户的用于显示的docAuth
	public DocAuth getUserDispDocAuth(Repos repos, Integer UserID, Doc doc)
	{
		System.out.println("getUserDispDocAuth() UserID:"+UserID);
		
		DocAuth docAuth = getUserDocAuth(repos, UserID, doc);	//获取用户真实的权限
		printObject("getUserDispDocAuth() docAuth:",docAuth);
		
		//Get UserName
		String UserName = getUserName(UserID);
		
		//转换成可显示的权限
		if(docAuth == null)
		{
			docAuth = new DocAuth();
			docAuth.setUserId(UserID);
			docAuth.setUserName(UserName);
			docAuth.setDocId(doc.getDocId());
			docAuth.setDocName(doc.getName());
			docAuth.setDocPath(doc.getPath());
			docAuth.setReposId(repos.getId());
		}
		else	//如果docAuth非空，需要判断是否是直接权限，如果不是需要对docAuth进行修改
		{
			printObject("getUserDispDocAuth() docAuth:",docAuth);
			if(docAuth.getUserId() == null || !docAuth.getUserId().equals(UserID) || !docAuth.getDocId().equals(doc.getDocId()))
			{
				System.out.println("getUserDispDocAuth() docAuth为继承的权限,需要删除reposAuthId并设置userID、UserName");
				docAuth.setId(null);	//clear docAuthID, so that we know this setting was not on user directly
			}
			
			docAuth.setUserId(UserID);
			docAuth.setUserName(UserName);
			docAuth.setDocId(doc.getDocId());
			docAuth.setDocName(doc.getName());
			docAuth.setDocPath(doc.getPath());
			docAuth.setReposId(repos.getId());
		}
		return docAuth;
	}

	protected DocAuth getGroupDocAuth(Repos repos, Integer groupId, Doc doc)
	{
		return getRealDocAuth(repos, null, groupId, doc);
	}
	
	protected DocAuth getUserDocAuth(Repos repos, Integer userId, Doc doc) 
	{
		return getRealDocAuth(repos, userId, null, doc);
	}
	
	//Function:getUserDocAuth
	protected DocAuth getRealDocAuth(Repos repos, Integer userId,Integer groupId, Doc doc) 
	{
		System.out.println("getRealDocAuth()  reposId:"+ repos.getId() + " userId:" + userId + " groupId:"+ groupId + " docId:" + doc.getDocId() + " parentPath:" + doc.getPath() + " docName:" + doc.getName());
		
		//获取从docId到rootDoc的全路径，put it to docPathList
		List<Long> docIdList = new ArrayList<Long>();
		docIdList = getDocIdList(repos, doc, docIdList);
		if(docIdList == null || docIdList.size() == 0)
		{
			return null;
		}
		printObject("getRealDocAuth() docIdList:",docIdList); 
		
		//Get UserDocAuthHashMap
		HashMap<Long, DocAuth> docAuthHashMap = null;
		if(userId != null)
		{
			docAuthHashMap = getUserDocAuthHashMap(userId,repos.getId());
		}
		else
		{
			docAuthHashMap = getGroupDocAuthHashMap(groupId,repos.getId());
		}
		
		//go throug the docIdList to get the UserDocAuthFromHashMap
		DocAuth parentDocAuth = null;
		DocAuth docAuth = null;
		int docPathDeepth = docIdList.size();
		for(int i= 0; i < docPathDeepth; i++)
		{
			Long curDocId = docIdList.get(i);
			System.out.println("getRealDocAuth() curDocId[" + i+ "]:" + curDocId); 
			docAuth = getDocAuthFromHashMap(curDocId,parentDocAuth,docAuthHashMap);
			parentDocAuth = docAuth;
		}		
		return docAuth;
	}

	protected List<Long> getDocIdList(Repos repos, Doc doc, List<Long> docIdList) 
	{
		if(doc.getDocId() == 0)
		{
			docIdList.add(0L);
			return docIdList;
		}
		
		String docPath = doc.getPath() + doc.getName();
		String [] paths = docPath.split("/");
		int docPathDeepth = paths.length;

		//RootDocId
		docIdList.add(0L);
		
		String tmpPath = "";
		String tmpName = "";
		for(int i=0; i<docPathDeepth; i++)
		{
			tmpName = paths[i];
			if(tmpName.isEmpty())
			{
				continue;
			}
			
			Long tempDocId = buildDocIdByName(i, tmpPath, tmpName);
			docIdList.add(tempDocId);
			
			tmpPath = tmpPath + tmpName + "/";
		}
		
		return docIdList;
	}
	
	protected HashMap<Long,DocAuth> getUserDocAuthHashMap(Integer UserID,Integer reposID) 
	{
		DocAuth docAuth = new DocAuth();
		docAuth.setUserId(UserID);			
		docAuth.setReposId(reposID);
	
		List <DocAuth> docAuthList = null;
		if(UserID == 0)
		{
			docAuthList = reposService.getDocAuthForAnyUser(docAuth);
		}
		else
		{
			docAuthList = reposService.getDocAuthForUser(docAuth);
		}
		printObject("getUserDocAuthHashMap() "+ "userID:" + UserID + " docAuthList:", docAuthList);
		
		if(docAuthList == null || docAuthList.size() == 0)
		{
			return null;
		}
		
		HashMap<Long,DocAuth> hashMap = BuildHashMapByDocAuthList(docAuthList);
		printObject("getUserDocAuthHashMap() "+ "userID:" + UserID + " hashMap:", hashMap);
		return hashMap;
	}
	
	//获取组在仓库上所有doc的权限设置: 仅用于显示group的权限
	protected HashMap<Long, DocAuth> getGroupDocAuthHashMap(Integer GroupID,Integer reposID) 
	{
		DocAuth docAuth = new DocAuth();
		docAuth.setGroupId(GroupID);
		docAuth.setReposId(reposID);
		List <DocAuth> docAuthList = reposService.getDocAuthForGroup(docAuth);
		printObject("getGroupDocAuthHashMap() GroupID[" + GroupID +"] docAuthList:", docAuthList);
		
		if(docAuthList == null || docAuthList.size() == 0)
		{
			return null;
		}
		
		HashMap<Long, DocAuth> hashMap = BuildHashMapByDocAuthList(docAuthList);
		printObject("getGroupDocAuthHashMap() GroupID[" + GroupID +"] hashMap:", hashMap);
		return hashMap;
	}
	
	protected Integer getAuthType(Integer userId, Integer groupId) {

		if(userId == null)
		{
			if(groupId != null)
			{
				return 2;
			}
			else
			{
				return null;
			}
		}
		else if(userId > 0)
		{
			return 3; //权限类型：用户权限
		}
		else
		{
			if(groupId != null)
			{
				return 2;
			}
			return 1; //权限类型：任意用户权限
		}
	}
	
	protected Integer getPriorityByAuthType(Integer type) {
		if(type == 3)
		{
			return 10;
		}
		else if(type == 2)
		{
			return 1;
		}
		else if(type ==1)
		{
			return 0;
		}
		return null;
	}
	
	protected void xorReposAuth(ReposAuth auth, ReposAuth tmpAuth) {
		if(tmpAuth.getIsAdmin()!=null && tmpAuth.getIsAdmin().equals(1))
		{
			auth.setIsAdmin(1);
		}
		if(tmpAuth.getAccess()!=null && tmpAuth.getAccess().equals(1))
		{
			auth.setAccess(1);
		}
		if(tmpAuth.getAddEn()!=null && tmpAuth.getAddEn().equals(1))
		{
			auth.setAddEn(1);
		}
		if(tmpAuth.getDeleteEn()!=null && tmpAuth.getDeleteEn().equals(1))
		{
			auth.setDeleteEn(1);
		}
		if(tmpAuth.getEditEn()!=null && tmpAuth.getEditEn().equals(1))
		{
			auth.setEditEn(1);
		}
		if(tmpAuth.getHeritable()!=null && tmpAuth.getHeritable().equals(1))
		{
			auth.setHeritable(1);
		}	
	}
	
	protected void xorDocAuth(DocAuth auth, DocAuth tmpAuth) {
		if(tmpAuth.getIsAdmin()!=null && tmpAuth.getIsAdmin().equals(1))
		{
			auth.setIsAdmin(1);
		}
		if(tmpAuth.getAccess()!=null && tmpAuth.getAccess().equals(1))
		{
			auth.setAccess(1);
		}
		if(tmpAuth.getAddEn()!=null && tmpAuth.getAddEn().equals(1))
		{
			auth.setAddEn(1);
		}
		if(tmpAuth.getDeleteEn()!=null && tmpAuth.getDeleteEn().equals(1))
		{
			auth.setDeleteEn(1);
		}
		if(tmpAuth.getEditEn()!=null && tmpAuth.getEditEn().equals(1))
		{
			auth.setEditEn(1);
		}
		if(tmpAuth.getHeritable()!=null && tmpAuth.getHeritable().equals(1))
		{
			auth.setHeritable(1);
		}	
	}
	
	//这是一个非常重要的底层接口，每个doc的权限都是使用这个接口获取的
	protected DocAuth getDocAuthFromHashMap(Long docId, DocAuth parentDocAuth,HashMap<Long,DocAuth> docAuthHashMap)
	{
		//System.out.println("getDocAuthFromHashMap() docId:" + docId);
		if(docAuthHashMap == null)
		{
			return null;
		}
		
		if(docId == null)
		{
			return null;
		}
		
		//For rootDoc parentDocAuth is useless
		if(docId == 0)
		{
			DocAuth docAuth = docAuthHashMap.get(docId);
			return docAuth;
		}
		
		//Not root Doc, if parentDocAuth is null, return null
		if(parentDocAuth == null)
		{
			System.out.println("getDocAuthFromHashMap() docId:" + docId + " parentDocAuth is null");
			return null;
		}
		
		//Not root Doc and parentDocAuth is set
		Integer parentPriority = parentDocAuth.getPriority();
		Integer parentHeritable = parentDocAuth.getHeritable();
		DocAuth docAuth = docAuthHashMap.get(docId);
		if(docAuth == null)
		{
			//设置为空，继承父节点权限
			if(parentHeritable == null || parentHeritable == 0)
			{
				//不可继承
				System.out.println("getDocAuthFromHashMap() docId:" + docId + "docAuth is null and parentHeritable is null or 0");
				return null;
			}
			return parentDocAuth;
		}
		else
		{
			if(docAuth.getPriority() >= parentPriority)
			{
				//Use the docAuth
				return docAuth;
			}
			else
			{
				//无效设置，则继承父节点权限
				if(parentHeritable == null || parentHeritable == 0)
				{
					//不可继承
					System.out.println("getDocAuthFromHashMap() docId:" + docId + " docAuth priority < parentPriority and parentHeritable is null or 0");
					return null;
				}
				return parentDocAuth;
			}
		}
	}
		
	protected HashMap<Integer,ReposAuth> BuildHashMapByReposAuthList(List<ReposAuth> reposAuthList) {
		//去重并将参数放入HashMap
		HashMap<Integer,ReposAuth> hashMap = new HashMap<Integer,ReposAuth>();
		for(int i=0;i<reposAuthList.size();i++)
		{
			ReposAuth reposAuth = reposAuthList.get(i);
			Integer reposId = reposAuth.getReposId();
			ReposAuth hashEntry = hashMap.get(reposId);
			if(hashEntry == null)
			{
				hashMap.put(reposId, reposAuth);
			}
			else
			{
				Integer oldPriority = hashEntry.getPriority();
				Integer newPriority = reposAuth.getPriority();
				if(newPriority > oldPriority)
				{
					//Update to new ReposAuth
					hashMap.put(reposId, reposAuth);
				}
				else if(newPriority == oldPriority)
				{
					xorReposAuth(hashEntry,reposAuth);
				}
			}
			
		}		
		return hashMap;
	}
	
	protected HashMap<Long,DocAuth> BuildHashMapByDocAuthList(List<DocAuth> docAuthList) {
		//去重并将参数放入HashMap
		HashMap<Long,DocAuth> hashMap = new HashMap<Long,DocAuth>();
		for(int i=0;i<docAuthList.size();i++)
		{
			DocAuth docAuth = docAuthList.get(i);
			Long docId = docAuth.getDocId();
			DocAuth hashEntry = hashMap.get(docId);
			if(hashEntry == null)
			{
				hashMap.put(docId, docAuth);
			}
			else
			{
				Integer oldPriority = hashEntry.getPriority();
				Integer newPriority = docAuth.getPriority();
				if(newPriority > oldPriority)
				{
					//Update to new DocAuth
					hashMap.put(docId, docAuth);
				}
				else if(newPriority == oldPriority)
				{
					xorDocAuth(hashEntry,docAuth);
				}
			}
			
		}		
		return hashMap;
	}

	
	/*************************** DocSys文件操作接口 ***********************************/
	//create Real Doc
	protected boolean createRealDoc(Repos repos, Doc doc, ReturnAjax rt) {
		System.out.println("createRealDoc() localRootPath:" + doc.getLocalRootPath() + " path:" + doc.getPath() + " name:" + doc.getName());
		
		String name = doc.getName();
		int type = doc.getType();
		
		//获取 doc parentPath
		String localParentPath =  doc.getLocalRootPath() + doc.getPath();

		String localDocPath = localParentPath + name;
		
		if(type == 2) //目录
		{
			if(false == createDir(localDocPath))
			{
				docSysDebugLog("createRealDoc() 目录 " +localDocPath + " 创建失败！", rt);
				return false;
			}				
		}
		else
		{
			if(false == createFile(localParentPath,name))
			{
				docSysDebugLog("createRealDoc() createFile 文件 " + localDocPath + "创建失败！", rt);
				return false;					
			}
		}
		return true;
	}
	
	protected boolean deleteRealDoc(Repos repos, Doc doc, ReturnAjax rt) {
		
		String reposRPath = getReposRealPath(repos);
		String parentPath = doc.getPath();
		String name = doc.getName();
		String localDocPath = reposRPath + parentPath + name;

		if(delFileOrDir(localDocPath) == false)
		{
			docSysDebugLog("deleteRealDoc() delFileOrDir " + localDocPath + "删除失败！", rt);
			return false;
		}
		
		return true;
	}
	
	protected boolean updateRealDoc(Repos repos, Doc doc, MultipartFile uploadFile, Integer chunkNum, Integer chunkSize, String chunkParentPath, ReturnAjax rt) 
	{
		String parentPath = doc.getPath();
		String name = doc.getName();
		Long fileSize = doc.getSize();
		String fileCheckSum = doc.getCheckSum();
		
		String reposRPath = getReposRealPath(repos);
		
		String localDocParentPath = reposRPath + parentPath;
		String retName = null;
		try {
			if(null == chunkNum)	//非分片上传
			{
				retName = saveFile(uploadFile, localDocParentPath,name);
			}
			else
			{
				retName = combineChunks(localDocParentPath,name,chunkNum,chunkSize,chunkParentPath);
			}
			//Verify the size and FileCheckSum
			if(false == checkFileSizeAndCheckSum(localDocParentPath,name,fileSize,fileCheckSum))
			{
				System.out.println("updateRealDoc() checkFileSizeAndCheckSum Error");
				return false;
			}
			
		} catch (Exception e) {
			System.out.println("updateRealDoc() saveFile " + name +" 异常！");
			docSysDebugLog(e.toString(), rt);
			e.printStackTrace();
			return false;
		}
		
		System.out.println("updateRealDoc() saveFile return: " + retName);
		if(retName == null  || !retName.equals(name))
		{
			System.out.println("updateRealDoc() saveFile " + name +" Failed！");
			return false;
		}
		return true;
	}
	
	protected String combineChunks(String targetParentPath,String fileName, Integer chunkNum,Integer cutSize, String chunkParentPath) {
		try {
			String targetFilePath = targetParentPath + fileName;
			FileOutputStream out;

			out = new FileOutputStream(targetFilePath);
	        FileChannel outputChannel = out.getChannel();   

        	long offset = 0;
	        for(int chunkIndex = 0; chunkIndex < chunkNum; chunkIndex ++)
	        {
	        	String chunkFilePath = chunkParentPath + fileName + "_" + chunkIndex;
	        	FileInputStream in=new FileInputStream(chunkFilePath);
	            FileChannel inputChannel = in.getChannel();    
	            outputChannel.transferFrom(inputChannel, offset, inputChannel.size());
	        	offset += inputChannel.size();	        			
	    	   	inputChannel.close();
	    	   	in.close();
	    	}
	        outputChannel.close();
		    out.close();
		    return fileName;
		} catch (Exception e) {
			System.out.println("combineChunks() Failed to combine the chunks");
			e.printStackTrace();
			return null;
		}        
	}
	
	protected void deleteChunks(String name, Integer chunkIndex, Integer chunkNum, String chunkParentPath) {
		System.out.println("deleteChunks() name:" + name + " chunkIndex:" + chunkIndex  + " chunkNum:" + chunkNum + " chunkParentPath:" + chunkParentPath);
		
		if(null == chunkIndex || chunkIndex < (chunkNum-1))
		{
			return;
		}
		
		System.out.println("deleteChunks() name:" + name + " chunkIndex:" + chunkIndex  + " chunkNum:" + chunkNum + " chunkParentPath:" + chunkParentPath);
		try {
	        for(int i = 0; i < chunkNum; i ++)
	        {
	        	String chunkFilePath = chunkParentPath + name + "_" + i;
	        	delFile(chunkFilePath);
	    	}
		} catch (Exception e) {
			System.out.println("deleteChunks() Failed to combine the chunks");
			e.printStackTrace();
		}  
	}

	protected boolean isChunkMatched(String chunkFilePath, String chunkHash) {
		//检查文件是否存在
		File f = new File(chunkFilePath);
		if(!f.exists()){
			return false;
		}

		//Check if chunkHash is same
		try {
			FileInputStream file = new FileInputStream(chunkFilePath);
			String hash=DigestUtils.md5Hex(file);
			file.close();
			if(hash.equals(chunkHash))
			{
				return true;
			}
		} catch (Exception e) {
			System.out.println("isChunkMatched() Exception"); 
			e.printStackTrace();
			return false;
		}

		return false;
	}
	
	protected boolean checkFileSizeAndCheckSum(String localDocParentPath, String name, Long fileSize,
			String fileCheckSum) {
		File file = new File(localDocParentPath,name);
		if(fileSize != file.length())
		{
			System.out.println("checkFileSizeAndCheckSum() fileSize " + file.length() + "not match with ExpectedSize" + fileSize);
			return false;
		}
		return true;
	}
	
	protected boolean moveRealDoc(Repos repos, Doc srcDoc, Doc dstDoc, ReturnAjax rt) 
	{
		String reposRPath = getReposRealPath(repos);
		String srcParentPath = srcDoc.getPath();
		String srcName = srcDoc.getName();
		String dstParentPath = dstDoc.getPath();
		String dstName = dstDoc.getName();
		
		String srcDocPath = reposRPath + srcParentPath + srcName;
		String dstDocPath = reposRPath + dstParentPath + dstName;

		if(isFileExist(srcDocPath) == false)
		{
			docSysDebugLog("moveRealDoc() 文件: " + srcDocPath + " 不存在", rt);
			return false;
		}
		
		if(isFileExist(dstDocPath) == true)
		{
			docSysDebugLog("moveRealDoc() 文件: " + dstDocPath + " 已存在", rt);
			return false;
		}
		
		if(moveFileOrDir(reposRPath + srcParentPath,srcName,reposRPath + dstParentPath,dstName,true) == false)	//强制覆盖
		{
			docSysDebugLog("moveRealDoc() move " + srcDocPath + " to "+ dstDocPath + " Failed", rt);
			return false;
		}
		return true;
	}
	
	protected boolean copyRealDoc(Repos repos, Doc srcDoc, Doc dstDoc, ReturnAjax rt) 
	{
		String reposRPath = getReposRealPath(repos);
		String srcParentPath = srcDoc.getPath();
		String srcName = srcDoc.getName();
		String dstParentPath = dstDoc.getPath();
		String dstName = dstDoc.getName();
		
		String srcDocPath = reposRPath + srcParentPath + srcName;
		String dstDocPath = reposRPath + dstParentPath + dstName;

		if(isFileExist(srcDocPath) == false)
		{
			docSysDebugLog("copyRealDoc() 文件: " + srcDocPath + " 不存在", rt);
			return false;
		}
		
		if(isFileExist(dstDocPath) == true)
		{
			docSysDebugLog("copyRealDoc() 文件: " + dstDocPath + " 已存在", rt);
			return false;
		}
		

		if(false == copyFileOrDir(srcDocPath, dstDocPath, true))
		{
			docSysDebugLog("copyRealDoc copy " + srcDocPath + " to " + dstDocPath + " 失败", rt);
			return false;
		}
		return true;
	}

	private boolean isVDocExist(Repos repos, Doc doc) {
		
		String vDocName = getVDocName(doc);
		return isFileExist(doc.getLocalVRootPath() + vDocName);
	}
	
	//create Virtual Doc
	protected boolean createVirtualDoc(Repos repos, Doc doc, ReturnAjax rt) 
	{
		String content = doc.getContent();
		if(content == null || content.isEmpty())
		{
			System.out.println("createVirtualDoc() content is empty");
			return false;
		}
				
		String docVName = getVDocName(doc);
		
		String vDocPath = doc.getLocalVRootPath() + docVName;
		System.out.println("vDocPath: " + vDocPath);
			
		if(false == createDir(vDocPath))
		{
			docSysDebugLog("目录 " + vDocPath + " 创建失败！", rt);
			return false;
		}
		if(createDir(vDocPath + "/res") == false)
		{
			docSysDebugLog("目录 " + vDocPath + "/res" + " 创建失败！", rt);
			return false;
		}
		if(createFile(vDocPath,"content.md") == false)
		{
			docSysDebugLog("目录 " + vDocPath + "/content.md" + " 创建失败！", rt);
			return false;			
		}
		
		return saveVirtualDocContent(repos, doc, rt);
	}
	
	protected String readOfficeContent(Repos repos, Doc doc, User login_user)
	{
		String userTmpDir = getReposUserTmpPathForOfficeTmp(repos,login_user);
		return readDocContentFromFile(userTmpDir, doc.getDocId() + "_" + doc.getName(), true);
	}
	
	protected boolean saveRealDocContent(Repos repos, Doc doc, ReturnAjax rt) 
	{	
		return saveDocContentToFile(doc.getContent(), doc.getLocalRootPath() + doc.getPath(), doc.getName());
	}
	protected String readRealDocContent(Repos repos, Doc doc) 
	{
		return readDocContentFromFile(doc.getLocalRootPath() + doc.getPath(), doc.getName(), true);
	}
	
	protected boolean saveTmpRealDocContent(Repos repos, Doc doc, User login_user, ReturnAjax rt) 
	{	
		String userTmpDir = getReposUserTmpPathForRDOC(repos,login_user);
		return saveDocContentToFile(doc.getContent(), userTmpDir, doc.getDocId() + "_" + doc.getName());
	}
	
	protected String readTmpRealDocContent(Repos repos, Doc doc, User login_user) 
	{
		String userTmpDir = getReposUserTmpPathForRDOC(repos,login_user);
		return readDocContentFromFile(userTmpDir, doc.getDocId() + "_" + doc.getName(), true);
	}
	
	protected boolean saveVirtualDocContent(Repos repos, Doc doc, ReturnAjax rt) 
	{	
		String docVName = getVDocName(doc);
		return saveDocContentToFile(doc.getContent(), doc.getLocalVRootPath() + docVName + "/", "content.md");
	}
	protected String readVirtualDocContent(Repos repos, Doc doc) 
	{
		String docVName = getVDocName(doc);		
		return readDocContentFromFile(doc.getLocalVRootPath() + docVName + "/", "content.md", false);
	}

	protected boolean saveTmpVirtualDocContent(Repos repos, Doc doc, User login_user, ReturnAjax rt) 
	{	
		String docVName = getVDocName(doc);
		String userTmpDir = getReposUserTmpPathForVDOC(repos,login_user);
		return saveDocContentToFile(doc.getContent(),  userTmpDir + docVName + "/", "content.md");
	}
	protected String readTmpVirtualDocContent(Repos repos, Doc doc, User login_user) 
	{
		String docVName = getVDocName(doc);		
		String userTmpDir = getReposUserTmpPathForVDOC(repos,login_user);
		return readDocContentFromFile(userTmpDir + docVName + "/", "content.md", false);
	}
	
	protected boolean deleteVirtualDoc(Repos repos, Doc doc, ReturnAjax rt) {
		String reposVPath = getReposVirtualPath(repos);
		String docVName = getVDocName(doc);
		
		String localDocVPath = reposVPath + docVName;
		if(delDir(localDocVPath) == false)
		{
			docSysDebugLog("deleteVirtualDoc() delDir失败 " + localDocVPath, rt);
			return false;
		}
		return true;
	}
	
	protected boolean moveVirtualDoc(Repos repos, Doc doc,Doc newDoc, ReturnAjax rt) 
	{
		String reposVPath = getReposVirtualPath(repos);
		
		String vDocName = getVDocName(doc);
		
		String newVDocName = getVDocName(newDoc);
				
		if(moveFileOrDir(reposVPath, vDocName, reposVPath, newVDocName, true) == false)
		{
			docSysDebugLog("moveVirtualDoc() moveFile " + reposVPath + vDocName+ " to " + reposVPath + newVDocName + " Failed", rt);
			return false;
		}
		return true;
	}
	
	protected boolean copyVirtualDoc(Repos repos, Doc doc,Doc newDoc, ReturnAjax rt) 
	{
		String reposVPath = getReposVirtualPath(repos);
		
		String vDocName = getVDocName(doc);
		
		String newVDocName = getVDocName(newDoc);
		
		String srcDocFullVPath = reposVPath + vDocName;
		String dstDocFullVPath = reposVPath + newVDocName;
		if(copyDir(srcDocFullVPath,dstDocFullVPath,true) == false)
		{
			docSysDebugLog("copyVirtualDoc() copyDir " + srcDocFullVPath +  " to " + dstDocFullVPath + " Failed", rt);
			return false;
		}
		return true;
	}
	
	//删除预览文件
	protected void deletePreviewFile(Doc doc) 
	{
		if(doc == null || doc.getCheckSum() == null)
		{
			return;
		}
		
		String dstName = doc.getVid() + "_" + doc.getDocId() + ".pdf";
		String dstPath = getWebTmpPathForPreview() + dstName;
		delFileOrDir(dstPath);
	}
	
	/*************** DocSys verRepos操作接口 *********************/
	protected List<LogEntry> verReposGetHistory(Repos repos,boolean convert, Doc doc, int maxLogNum) 
	{
		doc = docConvert(doc, convert);
		
		int verCtrl = getVerCtrl(repos, doc);
		if(verCtrl == 1)
		{
			return svnGetHistory(repos, doc, maxLogNum);
		}
		else if(verCtrl == 2)
		{
			return gitGetHistory(repos, doc, maxLogNum);
		}
		return null;
	}
	
	protected List<LogEntry> svnGetHistory(Repos repos, Doc doc, int maxLogNum) {

		SVNUtil svnUtil = new SVNUtil();
		if(false == svnUtil.Init(repos, doc.getIsRealDoc(), null))
		{
			System.out.println("svnGetHistory() svnUtil.Init Failed");
			return null;
		}
		return svnUtil.getHistoryLogs(doc.getPath() + doc.getName(), 0, -1, maxLogNum);
	}
	
	protected List<LogEntry> gitGetHistory(Repos repos, Doc doc, int maxLogNum) {
		GITUtil gitUtil = new GITUtil();
		if(false == gitUtil.Init(repos, doc.getIsRealDoc(), null))
		{
			System.out.println("gitGetHistory() gitUtil.Init Failed");
			return null;
		}
		return gitUtil.getHistoryLogs(doc.getPath() + doc.getName(), null, null, maxLogNum);
	}

	
	//Get History Detail
	protected List<ChangedItem> verReposGetHistoryDetail(Repos repos,boolean convert, Doc doc, String commitId) 
	{
		doc = docConvert(doc, convert);

		int verCtrl = getVerCtrl(repos, doc);
		if(verCtrl == 1)
		{
			return svnGetHistoryDetail(repos, doc, commitId);
		}
		else if(verCtrl == 2)
		{
			return gitGetHistoryDetail(repos, doc, commitId);
		}
		return null;
	}
	
	protected List<ChangedItem> svnGetHistoryDetail(Repos repos, Doc doc, String commitId) 
	{
		SVNUtil svnUtil = new SVNUtil();
		if(false == svnUtil.Init(repos, doc.getIsRealDoc(), null))
		{
			System.out.println("svnGetHistory() svnUtil.Init Failed");
			return null;
		}
		
		return svnUtil.getHistoryDetail(doc, commitId); 
	}
	
	protected List<ChangedItem> gitGetHistoryDetail(Repos repos, Doc doc, String commitId) 
	{
		GITUtil gitUtil = new GITUtil();
		if(false == gitUtil.Init(repos, doc.getIsRealDoc(), null))
		{
			System.out.println("gitGetHistory() gitUtil.Init Failed");
			return null;
		}
		
		return gitUtil.getHistoryDetail(doc, commitId);
	}
	
	protected String verReposDocCommit(Repos repos, boolean convert, Doc doc, String commitMsg, String commitUser, ReturnAjax rt, boolean modifyEnable, HashMap<Long, DocChange> localChanges, int subDocCommitFlag, List<CommitAction> commitActionList) 
	{	
		doc = docConvert(doc, convert);
		
		int verCtrl = getVerCtrl(repos, doc);
		
		System.out.println("verReposDocCommit verCtrl:"+verCtrl);
		if(verCtrl == 1)
		{
			commitMsg = commitMsgFormat(repos, doc.getIsRealDoc(), commitMsg, commitUser);
			return svnDocCommit(repos, doc, commitMsg, commitUser, rt, modifyEnable, localChanges, subDocCommitFlag, commitActionList);
		}
		else if(verCtrl == 2)
		{
			return gitDocCommit(repos, doc, commitMsg, commitUser, rt, modifyEnable, localChanges, subDocCommitFlag, commitActionList);
		}
		
		return "";
	}
	
	private int getVerCtrl(Repos repos, Doc doc) {
		int verCtrl = repos.getVerCtrl();
		if(doc.getIsRealDoc() == false)
		{
			verCtrl = repos.getVerCtrl1();
		}
		return verCtrl;
	}

	protected String svnDocCommit(Repos repos, Doc doc, String commitMsg, String commitUser, ReturnAjax rt, boolean modifyEnable, HashMap<Long, DocChange> localChanges, int subDocCommitFlag, List<CommitAction> commitActionList)
	{			
		boolean isRealDoc = doc.getIsRealDoc();
		
		SVNUtil verReposUtil = new SVNUtil();
		if(false == verReposUtil.Init(repos, isRealDoc, commitUser))
		{
			return null;
		}

		return verReposUtil.doAutoCommit(doc, commitMsg,commitUser,modifyEnable, localChanges, subDocCommitFlag, commitActionList);
	}
	
	protected String gitDocCommit(Repos repos, Doc doc,	String commitMsg, String commitUser, ReturnAjax rt, boolean modifyEnable, HashMap<Long, DocChange> localChanges, int subDocCommitFlag, List<CommitAction> commitActionList) 
	{
		boolean isRealDoc = doc.getIsRealDoc();
		
		GITUtil verReposUtil = new GITUtil();
		if(false == verReposUtil.Init(repos, isRealDoc, commitUser))
		{
			return null;
		}
		
		if(verReposUtil.checkAndClearnBranch() == false)
		{
			System.out.println("gitDocCommit() master branch is dirty and failed to clean");
			return null;
		}
		
		String revision =  verReposUtil.doAutoCommit(doc, commitMsg,commitUser,modifyEnable, localChanges, subDocCommitFlag, commitActionList);
		if(revision == null)
		{
			return null;
		}
		return revision;
	}
	
	private Integer verReposCheckPath(Repos repos, boolean convert, Doc doc, String commitId) 
	{	
		doc = docConvert(doc, convert);
		
		int verCtrl = getVerCtrl(repos, doc);		
		if(verCtrl == 1)
		{
			return svnCheckPath(repos, doc, commitId);		
		}
		else if(verCtrl == 2)
		{
			return gitCheckPath(repos, doc, commitId);
		}
		return null;
	}

	private Integer gitCheckPath(Repos repos, Doc doc, String commitId) {
		boolean isRealDoc = doc.getIsRealDoc();
		
		GITUtil verReposUtil = new GITUtil();
		if(false == verReposUtil.Init(repos, isRealDoc, ""))
		{
			return null;
		}
		
		String entryPath = doc.getPath() + doc.getName();
		return verReposUtil.checkPath(entryPath, commitId);
	}

	private Integer svnCheckPath(Repos repos, Doc doc, String commitId) {
		boolean isRealDoc = doc.getIsRealDoc();
		
		SVNUtil verReposUtil = new SVNUtil();
		if(false == verReposUtil.Init(repos, isRealDoc, ""))
		{
			return null;
		}

		String entryPath = doc.getPath() + doc.getName();
		return verReposUtil.checkPath(entryPath, commitId);
	}

	/*
	 * verReposCheckOut
	 * 参数：
	 * 	force: 如果本地target文件存在，false则跳过，否则强制替换
	 *  auto: 如果CommitId对应的是删除操作，自动checkOut上删除前的版本（通过checkPath来确定是否是删除操作，但也有可能只是通过移动和复制的相关历史，那么往前追溯可能是有问题的） 
	 */
	protected List<Doc> verReposCheckOut(Repos repos, boolean convert, Doc doc, String localParentPath, String targetName, String commitId, boolean force, boolean auto, HashMap<String,String> downloadList) 
	{
		doc = docConvert(doc, convert);
		
		int verCtrl = getVerCtrl(repos, doc);
		if(verCtrl == 1)
		{
			return svnCheckOut(repos, doc, localParentPath, targetName, commitId, force, auto, downloadList);		
		}
		else if(verCtrl == 2)
		{
			return gitCheckOut(repos, doc, localParentPath, targetName, commitId, force, auto, downloadList);
		}
		return null;
	}
	
	protected Doc docConvert(Doc doc, boolean convert) 
	{
		if(convert)
		{
			if(doc.getIsRealDoc() == false)
			{
				//Convert doc to vDoc
				doc = buildVDoc(doc);
			}
		}
		return doc;
	}

	protected List<Doc> svnCheckOut(Repos repos, Doc doc, String localParentPath,String targetName,String revision, boolean force, boolean auto, HashMap<String, String> downloadList)
	{
		boolean isRealDoc = doc.getIsRealDoc();
		
		SVNUtil verReposUtil = new SVNUtil();
		if(false == verReposUtil.Init(repos, isRealDoc, ""))
		{
			return null;
		}

		String entryPath = doc.getPath() + doc.getName();
		Integer type = verReposUtil.checkPath(entryPath, revision);
    	if(type == null)
    	{
    		System.out.println("svnCheckOut() checkPath for " + entryPath + " 异常");
    		return null;
    	}
    	else if(type == 0)
    	{
    		System.out.println("svnCheckOut() " + entryPath + " not exists for revision:" + revision);
    		if(auto == false)
    		{
        		return null;
    		}

    		String preCommitId = verReposUtil.getReposPreviousCommmitId(revision);
    		if(preCommitId == null)
    		{
        		System.out.println("svnCheckOut() getPreviousCommmitId for revision:" + revision + " 异常");
    			return null;
    		}
    		revision = preCommitId;
    		System.out.println("svnCheckOut() try to chekout " + entryPath + " at revision:" + revision);
    	}
    	else
    	{
	    	if(doc.getName().isEmpty())
	    	{
	    		System.out.println("svnCheckOut() it is root doc, if there is no any subEntries means all items be deleted, we need to get preCommitId");
	    		Collection<SVNDirEntry> subEntries = verReposUtil.getSubEntries("", revision);
	    		if(verReposUtil.subEntriesIsEmpty(subEntries))
	    		{
	    	    	System.out.println("svnCheckOut() 根目录下没有文件 at revision:" + revision);
	        		if(auto == false)
	        		{
	        			return null;
	        		}
	        		
	    	    	String preCommitId = verReposUtil.getReposPreviousCommmitId(revision);
	    	    	if(preCommitId == null)
	    	    	{
	    	        	System.out.println("svnCheckOut() getPreviousCommmitId for revision:" + revision + " 异常");
	    	    		return null;
	    	    	}
	    	    	revision = preCommitId;
	    	    	System.out.println("svnCheckOut() try to chekout 根目录 at revision:" + revision);
	    		}
	    	}
    	}	
		return verReposUtil.getEntry(doc, localParentPath, targetName, revision, force, downloadList);
	}
	
	protected List<Doc> gitCheckOut(Repos repos, Doc doc, String localParentPath, String targetName, String revision, boolean force, boolean auto, HashMap<String, String> downloadList) 
	{
		boolean isRealDoc = doc.getIsRealDoc();
		
		GITUtil verReposUtil = new GITUtil();
		if(false == verReposUtil.Init(repos, isRealDoc, ""))
		{
			return null;
		}
		
		String entryPath = doc.getPath() + doc.getName();
		Integer type = verReposUtil.checkPath(entryPath, revision);
    	if(type == null)
    	{
    		System.out.println("gitCheckOut() checkPath for " + entryPath + " 异常");
    		return null;
    	}
    	else if(type == 0)
    	{
    		System.out.println("gitCheckOut() " + entryPath + " not exists for revision:" + revision);
    		if(auto == false)
    		{
        		return null;
    		}

    		String preCommitId = verReposUtil.getReposPreviousCommmitId(revision);
    		if(preCommitId == null)
    		{
        		System.out.println("gitCheckOut() getPreviousCommmitId for revision:" + revision + " 异常");
    			return null;
    		}
    		revision = preCommitId;
    		System.out.println("gitCheckOut() try to chekout " + entryPath + " at revision:" + revision);
    	}
    	else
    	{
	    	if(doc.getName().isEmpty())
	    	{
	    		System.out.println("gitCheckOut() it is root doc, if there is no any subEntries means all items be deleted, we need to get preCommitId");
	    		TreeWalk subEntries = verReposUtil.getSubEntries("", revision);
	    		if(verReposUtil.subEntriesIsEmpty(subEntries))
	    		{
	    	    	System.out.println("gitCheckOut() 根目录下没有文件 at revision:" + revision);
	        		if(auto == false)
	        		{
	        			return null;
	        		}
	        		
	    	    	String preCommitId = verReposUtil.getReposPreviousCommmitId(revision);
	    	    	if(preCommitId == null)
	    	    	{
	    	        	System.out.println("gitCheckOut() getPreviousCommmitId for revision:" + revision + " 异常");
	    	    		return null;
	    	    	}
	    	    	revision = preCommitId;
	    	    	System.out.println("gitCheckOut() try to chekout 根目录 at revision:" + revision);
	    		}
	    	}
    	}

		return verReposUtil.getEntry(doc, localParentPath, targetName, revision, force, downloadList);
	}

	protected String verReposDocMove(Repos repos,  boolean convert, Doc srcDoc, Doc dstDoc, String commitMsg, String commitUser, ReturnAjax rt, List<CommitAction> commitActionList) 
	{
		srcDoc = docConvert(srcDoc, convert);
		dstDoc = docConvert(dstDoc, convert);
		
		int verCtrl = getVerCtrl(repos, srcDoc);
		if(verCtrl == 1)
		{
			commitMsg = commitMsgFormat(repos, srcDoc.getIsRealDoc(), commitMsg, commitUser);
			return svnDocMove(repos, srcDoc, dstDoc, commitMsg, commitUser, rt, commitActionList);			
		}
		else if(verCtrl == 2)
		{
			return gitDocMove(repos, srcDoc, dstDoc, commitMsg, commitUser, rt, commitActionList);
		}
		return null;
	}
	
	private String svnDocMove(Repos repos, Doc srcDoc, Doc dstDoc, String commitMsg, String commitUser, ReturnAjax rt, List<CommitAction> commitActionList) {
		boolean isRealDoc = srcDoc.getIsRealDoc();
		
		SVNUtil verReposUtil = new SVNUtil();
		if(false == verReposUtil.Init(repos, isRealDoc, ""))
		{
			return null;
		}

		return verReposUtil.copyDoc(srcDoc, dstDoc, commitMsg, commitUser, true, commitActionList);
	}

	protected String gitDocMove(Repos repos, Doc srcDoc, Doc dstDoc, String commitMsg, String commitUser, ReturnAjax rt, List<CommitAction> commitActionList) 
	{
		boolean isRealDoc = srcDoc.getIsRealDoc();
		
		GITUtil verReposUtil = new GITUtil();
		if(false == verReposUtil.Init(repos, isRealDoc, ""))
		{
			return null;
		}

		return verReposUtil.copyDoc(srcDoc, dstDoc, commitMsg, commitUser,true, commitActionList);
	}
	
	protected String verReposDocCopy(Repos repos, boolean convert, Doc srcDoc, Doc dstDoc, String commitMsg, String commitUser, ReturnAjax rt, List<CommitAction> commitActionList) 
	{
		srcDoc = docConvert(srcDoc, convert);
		dstDoc = docConvert(dstDoc, convert);
		
		int verCtrl = getVerCtrl(repos, srcDoc);
		if(verCtrl == 1)
		{
			commitMsg = commitMsgFormat(repos, srcDoc.getIsRealDoc(), commitMsg, commitUser);
			return svnDocCopy(repos, srcDoc, dstDoc, commitMsg, commitUser, rt, commitActionList);		
		}
		else if(verCtrl == 2)
		{
			return gitDocCopy(repos, srcDoc, dstDoc, commitMsg, commitUser, rt, commitActionList);
		}
		return null;
	}
	
	
	private String svnDocCopy(Repos repos, Doc srcDoc, Doc dstDoc, String commitMsg, String commitUser, ReturnAjax rt, List<CommitAction> commitActionList) {
		boolean isRealDoc = srcDoc.getIsRealDoc();
		
		SVNUtil verReposUtil = new SVNUtil();
		if(false == verReposUtil.Init(repos, isRealDoc, ""))
		{
			return null;
		}

		return verReposUtil.copyDoc(srcDoc, dstDoc, commitMsg, commitUser, false, commitActionList);
	}

	protected String gitDocCopy(Repos repos, Doc srcDoc, Doc dstDoc, String commitMsg, String commitUser, ReturnAjax rt, List<CommitAction> commitActionList) 
	{
		boolean isRealDoc = srcDoc.getIsRealDoc();
		
		GITUtil verReposUtil = new GITUtil();
		if(false == verReposUtil.Init(repos, isRealDoc, ""))
		{
			return null;
		}
		
		return verReposUtil.copyDoc(srcDoc, dstDoc, commitMsg, commitUser, false, commitActionList);
	}
	
	protected String commitMsgFormat(Repos repos, boolean isRealDoc, String commitMsg, String commitUser) 
	{
		if(isRealDoc)
		{
			if(repos.getSvnUser() == null || repos.getSvnUser().isEmpty())
			{
				return commitMsg;
			}
		}
		else
		{
			if(repos.getSvnUser1() == null  || repos.getSvnUser1().isEmpty())
			{
				return commitMsg;
			}	
		}
		
		commitMsg = commitMsg + " [" + commitUser + "] ";
		return commitMsg;
	}

	protected HashMap<String,Doc> BuildHashMapByDocList(List<Doc> docList) {
		HashMap<String,Doc> hashMap = new HashMap<String,Doc>();
		for(int i=0;i<docList.size();i++)
		{
			Doc doc = docList.get(i);
			String docName = doc.getName();
			hashMap.put(docName, doc);			
		}		
		return hashMap;
	}
	
    /************************* DocSys全文搜索操作接口 ***********************************/
	protected static String getIndexLibPath(Repos repos, int indexLibType) 
	{
		String lucenePath = repos.getPath() + "DocSysLucene/";
		
		String indexLib = null;
		switch(indexLibType)
		{
		case 0:
			indexLib = "repos_" + repos.getId() + "_DocName";
			break;
		case 1:
			indexLib = "repos_" + repos.getId() + "_RDoc";
			break;
		case 2:
			indexLib = "repos_" + repos.getId() + "_VDoc";
			break;
		}
		
		return lucenePath + indexLib;
	}
	
	protected boolean deleteIndexLib(Repos repos, int indexLibType)
	{
		String libPath = getIndexLibPath(repos, indexLibType);
		System.out.println("deleteIndexLib() libPath:" + libPath);
		return LuceneUtil2.deleteIndexLib(libPath);
	}
	
	boolean deleteDocNameIndexLib(Repos repos)
	{
		return deleteIndexLib(repos, 0);
	}
	
	boolean deleteRDocIndexLib(Repos repos)
	{
		return deleteIndexLib(repos, 1);
	}

	boolean deleteVDocIndexLib(Repos repos)
	{
		return deleteIndexLib(repos, 2);
	}

	//Add Index For DocName
	public boolean addIndexForDocName(Repos repos, Doc doc, ReturnAjax rt)
	{
		//System.out.println("addIndexForDocName() docId:" + doc.getDocId() + " parentPath:" + doc.getPath() + " name:" + doc.getName() + " repos:" + repos.getName());
		String indexLib = getIndexLibPath(repos,0);

		return LuceneUtil2.addIndex(doc, getDocPath(doc), indexLib);
	}

	//Delete Indexs For DocName
	public static boolean deleteIndexForDocName(Repos repos, Doc doc, ReturnAjax rt)
	{
		System.out.println("deleteIndexForDocName() docId:" + doc.getDocId() + " parentPath:" + doc.getPath() + " name:" + doc.getName() + " repos:" + repos.getName());
		
		String indexLib = getIndexLibPath(repos,0);

		return LuceneUtil2.deleteIndex(doc, indexLib);
	}
		
	//Update Index For DocName
	public static boolean updateIndexForDocName(Repos repos, Doc doc, Doc newDoc, ReturnAjax rt)
	{
		System.out.println("updateIndexForDocName() docId:" +  doc.getDocId() + " parentPath:" +  doc.getPath()  + " name:" + doc.getName()  + " newParentPath:" + newDoc.getPath() + " newName:" + newDoc.getName() + " repos:" + repos.getName());

		String indexLib = getIndexLibPath(repos,0);

		String name = doc.getName();
		String newName = newDoc.getName();
		String parentPath = doc.getPath();
		String newParentPath = newDoc.getPath();
		if(name.equals(newName) && parentPath.equals(newParentPath))
		{
			System.out.println("updateIndexForDocName() Doc not Changed docId:" + doc.getDocId() + " parentPath:" + parentPath + " name:" + name + " newParentPath:" + newParentPath + " newName:" + newName);			
			return true;
		}
		
		LuceneUtil2.deleteIndex(doc, indexLib);

		String content = newParentPath + newName;
		return LuceneUtil2.addIndex(newDoc, content.trim(), indexLib);
	}

	//Add Index For VDoc
	public boolean addIndexForVDoc(Repos repos, Doc doc)
	{
		//System.out.println("addIndexForVDoc() docId:" + doc.getDocId() + " parentPath:" + doc.getPath() + " name:" + doc.getName() + " repos:" + repos.getName());

		String content = doc.getContent();
		if(content == null)
		{
			content = readVirtualDocContent(repos, doc);
		}
		
		String indexLib = getIndexLibPath(repos,2);

		if(content == null || content.isEmpty())
		{
			//System.out.println("addIndexForVDoc() content is null or empty, do delete Index");
			return LuceneUtil2.deleteIndex(doc, indexLib);			
		}
		
		return LuceneUtil2.addIndex(doc, content.toString().trim(), indexLib);
	}
	
	//Delete Indexs For VDoc
	public static boolean deleteIndexForVDoc(Repos repos, Doc doc)
	{
		System.out.println("deleteIndexForVDoc() docId:" + doc.getDocId() + " parentPath:" + doc.getPath() + " name:" + doc.getName() + " repos:" + repos.getName());
		
		String indexLib = getIndexLibPath(repos,2);
		
		return LuceneUtil2.deleteIndex(doc, indexLib);
	}
	
	//Update Index For VDoc
	public boolean updateIndexForVDoc(Repos repos, Doc doc)
	{
		System.out.println("updateIndexForVDoc() docId:" +  doc.getDocId() + " parentPath:" +  doc.getPath()  + " name:" + doc.getName() + " repos:" + repos.getName());

		String indexLib = getIndexLibPath(repos,2);
		
		String content = doc.getContent();
		if(content == null)
		{
			content = readVirtualDocContent(repos, doc);
		}		
		
		LuceneUtil2.deleteIndex(doc, indexLib);

		return LuceneUtil2.addIndex(doc, content.trim(), indexLib);
	}
		
	//Add Index For RDoc
	public static boolean addIndexForRDoc(Repos repos, Doc doc)
	{		
		//System.out.println("addIndexForRDoc() docId:" + doc.getDocId() + " parentPath:" + doc.getPath() + " name:" + doc.getName() + " repos:" + repos.getName());
		
		String indexLib = getIndexLibPath(repos, 1);

		String localRootPath = getReposRealPath(repos);
		String localParentPath = localRootPath + doc.getPath();
		String filePath = localParentPath + doc.getName();
				
		File file =new File(filePath);
		if(!file.exists())
		{
			System.out.println("addIndexForRDoc() " + filePath + " 不存在");
			return false;
		}
		
		if(file.isDirectory())
		{
			//System.out.println("addIndexForRDoc() isDirectory");
			return false; //LuceneUtil2.addIndex(LuceneUtil2.buildDocumentId(hashId,0), reposId, docId, parentPath, name, hashId, "", indexLib);
		}
		
		if(file.length() == 0)
		{
			//System.out.println("addIndexForRDoc() fileSize is 0, do delete index");
			return LuceneUtil2.deleteIndex(doc,indexLib);
		}
		
		//According the fileSuffix to confirm if it is Word/Execl/ppt/pdf
		String fileSuffix = getFileSuffix(doc.getName());
		if(fileSuffix != null)
		{
			System.out.println("addIndexForRDoc() docId:" + doc.getDocId() + " parentPath:" + doc.getPath() + " name:" + doc.getName() + " repos:" + repos.getName());
			switch(fileSuffix)
			{
			case "doc":
				return LuceneUtil2.addIndexForWord(filePath, doc, indexLib);
			case "docx":
				return LuceneUtil2.addIndexForWord2007(filePath, doc, indexLib);
			case "xls":
				return LuceneUtil2.addIndexForExcel(filePath, doc, indexLib);
			case "xlsx":
				return LuceneUtil2.addIndexForExcel2007(filePath, doc, indexLib);
			case "ppt":
				return LuceneUtil2.addIndexForPPT(filePath, doc, indexLib);
			case "pptx":
				return LuceneUtil2.addIndexForPPT2007(filePath, doc, indexLib);
			case "pdf":
				return LuceneUtil2.addIndexForPdf(filePath, doc, indexLib);
			default:
				if(isText(fileSuffix))
				{
					return LuceneUtil2.addIndexForFile(filePath, doc, indexLib);
				}
				break;
			}
		}

		//System.out.println("addIndexForRDoc() 未知文件类型不支持索引");
		return false;
	}

	public static boolean deleteIndexForRDoc(Repos repos, Doc doc)
	{
		System.out.println("deleteIndexForRDoc() docId:" + doc.getDocId() + " parentPath:" + doc.getPath() + " name:" + doc.getName() + " repos:" + repos.getName());
		
		String indexLib = getIndexLibPath(repos, 1);
			
		return LuceneUtil2.deleteIndex(doc,indexLib);
	}
	
	//Update Index For RDoc
	public static boolean updateIndexForRDoc(Repos repos, Doc doc)
	{
		System.out.println("updateIndexForRDoc() docId:" + doc.getDocId() + " parentPath:" + doc.getPath() + " name:" + doc.getName() + " repos:" + repos.getName());		
		deleteIndexForRDoc(repos, doc);
		return addIndexForRDoc(repos, doc);
	}
	
	/****************************DocSys系统初始化接口 *********************************/
    static final String JDBC_DRIVER = "com.mysql.jdbc.Driver";  
    static String DB_URL = "jdbc:mysql://localhost:3306/docsystem?zeroDateTimeBehavior=convertToNull&characterEncoding=utf8";
    static String DB_USER = "root";
    static String DB_PASS = "";
    static String docSysIniPath = null;
    static String docSysWebPath = null;
	
    //定义数据库的ObjType
    protected final static int DOCSYS_REPOS			=0;
    protected final static int DOCSYS_REPOS_AUTH	=1;
    protected final static int DOCSYS_DOC			=2;
	protected final static int DOCSYS_DOC_AUTH		=3;
	protected final static int DOCSYS_DOC_LOCK		=4;
	protected final static int DOCSYS_USER			=5;
	protected final static int DOCSYS_ROLE			=6;
	protected final static int DOCSYS_USER_GROUP	=7;
	protected final static int DOCSYS_GROUP_MEMBER	=8;
	protected final static int DOCSYS_SYS_CONFIG	=9;
	protected final static String [] DBTabNameMap = {
			"REPOS",
			"REPOS_AUTH",
			"DOC",
			"DOC_AUTH",
			"DOC_LOCK",
			"USER",
			"ROLE",
			"USER_GROUP",
			"GROUP_MEMBER",
			"SYS_CONFIG",
	};
	
	//系统需要根据该标志是否跳转至系统初始化配置页面（以便用户能够重新配置数据库）
	static Integer docSysIniState = 0;
	protected void docSysInit() 
	{	
		//Update the value of DB_URL/DB_USER/DB_PASS
		getAndSetDBInfo();
		String dbName = getDBNameFromUrl(DB_URL);
		
		docSysWebPath = getWebPath();
		docSysIniPath = docSysWebPath + "../docSys.ini/";
		File docSysIniDir = new File(docSysIniPath);
		if(docSysIniDir.exists() == false)
		{
			docSysIniDir.mkdirs();
			//检查docsystem数据库是否存在
			if(testDB(DB_URL, DB_USER, DB_PASS) == true)	//数据库存在
			{
				setDocSysInitState("{action: '检查数据库', step: 0, status: 'OK'}");
				return;
			}
						
			if(createDB(dbName) == false)
			{
				docSysIniState = 1;
				setDocSysInitState("{action: '新建数据库', step: 0, status: 'ERROR'}");
				return;
			}
			setDocSysInitState("{action: '新建数据库', step: 0, status: 'OK'}");
			
			if(initDB() == false)
			{
				docSysIniState = 2;
				setDocSysInitState("{action: '新建数据库', step: 1, status: 'ERROR'}");
				return;
			}
			setDocSysInitState("{action: '新建数据库', step: 1, status: 'OK'}");
			return;
		}

		if(isFileExist(docSysIniPath + "State") == true)
		{
			System.out.println("数据库升级操作已执行，如需重新执行请删除文件：" + docSysIniPath + "State");
			return;
		}

		
		//docSys.ini存在，则需要根据里面的版本号信息更新数据库
		//get the version info in war
		Integer version = getVersionFromFile(docSysWebPath, "version");
		//get the version info in docSys.ini
		Integer newVersion = getVersionFromFile(docSysIniPath, "newVersion");
		Integer oldVersion = getVersionFromFile(docSysIniPath , "oldVersion");
		if(newVersion == null || version == null || !version.equals(newVersion))
		{
			setDocSysInitState("{action: '升级数据库', step: 0, status: 'ERROR'}");
			return;
		}		
		
		if(checkAndUpdateDB(oldVersion, newVersion) == false)
		{
			docSysIniState = 3;
			setDocSysInitState("{action: '升级数据库', step: 1,  status: 'ERROR'}");
			return;
		}
		setDocSysInitState("{action: '升级数据库', step: 1, status: 'OK'}");
	}
	
	private void setDocSysInitState(String State) {
		saveDocContentToFile(State, docSysIniPath, "State");
	}

	private boolean checkAndUpdateWar() {
		System.out.println("checkAndUpdateWar()");
		
		if(isFileExist(docSysIniPath + "config") == false)
		{
			//no need to update the war
			System.out.println("checkAndUpdateWar() config not exists, no need to update war");
			return true;
		}
		
		//Copy DocSystem
		System.out.println("checkAndUpdateWar() copy " + docSysWebPath + " to " + docSysIniPath + "DocSystem");
		if(copyDir(docSysWebPath, docSysIniPath + "DocSystem", true) == false)
		{
			//Failed to copy 
			System.out.println("checkAndUpdateWar() Failed to copy " + docSysWebPath + " to " + docSysIniPath + "DocSystem");
			return false;
		}
		System.out.println("checkAndUpdateWar() Success to copy " + docSysWebPath + " to " + docSysIniPath + "DocSystem");

		System.out.println("checkAndUpdateWar() Start to copy config");
		if(copyDir(docSysIniPath + "config", docSysIniPath + "DocSystem/WEB-INF/classes", true) == false)
		{
			System.out.println("checkAndUpdateWar() Failed to copy config");
			return false;
		}	
		System.out.println("checkAndUpdateWar() Success to copy config");
		
		System.out.println("checkAndUpdateWar() Start to build new war");
		if(doCompressDir(docSysIniPath, "DocSystem", docSysIniPath, "DocSystem.war", null) == false)
		{
			System.out.println("checkAndUpdateWar() Failed to build new war");
			return false;
		}		
		System.out.println("checkAndUpdateWar() Success to build new war");
		
		System.out.println("checkAndUpdateWar() Start to copy new war");
		if(copyFile(docSysIniPath + "DocSystem.war", docSysWebPath + "../", true) == false)
		{
			System.out.println("checkAndUpdateWar() Failed to copy new war");
			return false;
		}
		System.out.println("checkAndUpdateWar() Success to copy new war");
		return true;
	}

	private boolean checkAndUpdateDB(Integer oldVersion, Integer newVersion) {
		System.out.println("checkAndUpdateDB() from " + oldVersion + " to " + newVersion);		
		String dbName = getDBNameFromUrl(DB_URL);
		//检查docsystem数据库是否存在
		if(testDB(DB_URL, DB_USER, DB_PASS) == false)	//数据库不存在
		{
			if(createDB(dbName) == false)
			{
				return false;
			}
			
			if(initDB() == false)
			{
				return false;
			}
			return true;
		}
		
		if(oldVersion == null)
		{
			oldVersion = 0;
		}
		return DBUpgrade(oldVersion, newVersion);
	}
	
    private static boolean executeSqlScript(String filePath) 
    {
        try {
            Connection conn = (Connection) DriverManager.getConnection(DB_URL ,DB_USER, DB_PASS);
            ScriptRunner runner = new ScriptRunner(conn);
            Resources.setCharset(Charset.forName("UTF-8")); //设置字符集,不然中文乱码插入错误
            runner.setLogWriter(null);//设置是否输出日志
            
            // 从class目录下直接读取
            Reader read = Resources.getResourceAsReader(filePath);
            runner.runScript(read);
            runner.closeConnection();
            conn.close();
            System.out.println("sql脚本执行完毕");
            return true;
        } catch (Exception e) {
            System.out.println("sql脚本执行发生异常");
            e.printStackTrace();
        }
		return false;
	}

	private boolean createDB(String dbName) 
    {
        try {
			Class.forName(JDBC_DRIVER);
		} catch (ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return false;
		}
        
        
		boolean ret = false;
		Connection conn = null;
        Statement stmt = null;
        try{
            // 注册 JDBC 驱动
            Class.forName(JDBC_DRIVER);
        
            // 打开链接
            String url = "jdbc:mysql://localhost:3306/test?zeroDateTimeBehavior=convertToNull&characterEncoding=utf8";
            //String url = "jdbc:mysql://localhost:3306/test?useUnicode=true&characterEncoding=utf8&serverTimezone=GMT";   
            conn = (Connection) DriverManager.getConnection(url ,DB_USER, DB_PASS);
        
            stmt = (Statement) conn.createStatement();
            String checkdatabase="show databases like \"" + dbName+ "\""; //判断数据库是否存在
	    	String createdatabase="create  database  " + dbName;	//创建数据库     
	    	ResultSet resultSet = stmt.executeQuery(checkdatabase);
	    	if (resultSet.next()) 
	    	{
	    		//若数据库存在
	    		System.out.println("createDB " + dbName + " exist!");
	    		stmt.close();
	    		conn.close();
	    		return true;
	    	}

	    	if(stmt.executeUpdate(createdatabase) == 0)		 
	    	{
	    		System.out.println("create table success!");
	    		stmt.close();
	    		conn.close();
	    		return true;
	    	}   
	    	
            // 完成后关闭
            stmt.close();
            conn.close();
        }catch(SQLException se){
            // 处理 JDBC 错误
            se.printStackTrace();
        }catch(Exception e){
            // 处理 Class.forName 错误
            e.printStackTrace();
        }finally{
            // 关闭资源
            try{
                if(stmt!=null) stmt.close();
            }catch(SQLException se2){
            }// 什么都不做
            try{
                if(conn!=null) conn.close();
            }catch(SQLException se){
                se.printStackTrace();
            }
        }
		return ret;
	}

	private static String getDBNameFromUrl(String url) 
	{
		String[] urlParts = url.split("\\?");
		if(urlParts == null || urlParts.length == 0)
		{
			return null;
		}
		
		String baseUrl = urlParts[0];
		String[] subStrs = baseUrl.split("/");
		if(subStrs == null || subStrs.length < 2)
		{
			return null;
		}
		
		return subStrs[subStrs.length-1];
	}

	public boolean testDB(String url, String user, String pwd)
    {
        Connection conn = null;
        try {
			Class.forName(JDBC_DRIVER);
		} catch (ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    
        // 打开链接
        System.out.println("连接数据库...");
        try {
			conn = (Connection) DriverManager.getConnection(url, user, pwd);
            conn.close();
    		System.out.println("连接数据库成功");
            return true;
		} catch (SQLException e) {
			System.out.println("连接数据库失败");
			e.printStackTrace();
		} finally{
            try{
                if(conn!=null) conn.close();
            }catch(SQLException se){
                se.printStackTrace();
            }
        }
        return false;        
    }
    
	private boolean getAndSetDBInfo()
	{
		String defaultJDBCSettingPath = docSysWebPath + "WEB-INF/classes/jdbc.properties";
		if(isFileExist(defaultJDBCSettingPath))
		{
			return getAndSetDBInfoFromFile(defaultJDBCSettingPath);
		}
		return false;
	}

	private boolean getAndSetDBInfoFromFile(String JDBCSettingPath) {
		System.out.println("getAndSetDBInfoFromFile " + JDBCSettingPath );

		String jdbcUrl = ReadProperties.getValue(JDBCSettingPath, "db.url");
		if(jdbcUrl == null || "".equals(jdbcUrl))
		{
			return false;
		}
		DB_URL = jdbcUrl;
		
		String jdbcUser = ReadProperties.getValue(JDBCSettingPath, "db.username");
		if(jdbcUser != null)
		{
			DB_USER = jdbcUser;
		}
		
		String jdbcPwd = ReadProperties.getValue(JDBCSettingPath, "db.password");
		if(jdbcPwd != null)
		{
			DB_PASS = jdbcPwd;
		}
		System.out.println("getAndSetDBInfoFromFile DB_URL:" + DB_URL + " DB_USER:" + DB_USER + " DB_PASS:" + DB_PASS);
		return true;
	}

	private Integer getVersionFromFile(String path, String name) 
	{
		String versionStr = readDocContentFromFile(path, name, false);
		System.out.println("getVersionFromFile() versionStr:" + versionStr);

		if(versionStr == null || versionStr.isEmpty())
		{
			return null;
		}
		
		int version = 0;
		String [] versions = versionStr.split("\\."); //.需要转义
		//System.out.println("getVersionFromFile() versions.length:" + versions.length); 
		
		for(int i=0; i<versions.length; i++)
		{
			//xx.xx.xx超过3级的忽略
			if(i > 2)
			{
				break;
			}
			
			String tmp = versions[i];
			//System.out.println("getVersionFromFile() tmp:" + tmp);

			if(tmp.isEmpty())
			{
				//非法版本号
				return null;
			}
			
			int tmpVersion = Integer.parseInt(tmp);
			//System.out.println("getVersionFromFile() tmpVersion:" + tmpVersion);
			if(tmpVersion > 99)
			{
				//非法版本号
				return null;
			}
			
			if(i == 0)
			{
				tmpVersion = tmpVersion*10000;
			}
			else if(i == 1)
			{
				tmpVersion = tmpVersion*100;				
			}
			//System.out.println("getVersionFromFile() tmpVersion:" + tmpVersion);
			
			version += tmpVersion;
		}
		
		System.out.println("getVersionFromFile() version:" + version);
		return version;
	}

	private static boolean DBUpgrade(int oldVersion, int newVersion)
	{
		System.out.println("DBUpgrade() from " + oldVersion + " to " + newVersion);
		
		List<Integer> dbTabsNeedToUpgrade = getDBTabListForUpgarde(oldVersion, newVersion);
		if(dbTabsNeedToUpgrade == null || dbTabsNeedToUpgrade.size() == 0)
		{
			System.out.println("DBUpgrade() no DB Table need to upgrade from " + oldVersion + " to " + newVersion);
			return true;
		}
		
		//由于以下操作存在导致数据库数据全部丢失的风险，因此必须先完成数据库完整备份
		Date date = new Date();
		String backUpTime = DateFormat.dateTimeFormat2(date);
		String backUpPath = docSysIniPath + "backup/" + backUpTime + "/";
		if(backupDB(backUpPath, "docsystem.sql") == false)
		{
			System.out.println("DBUpgrade() 数据库备份失败!");
			return true;
		}
		
		//export dbTab to json File
		for(int i=0; i< dbTabsNeedToUpgrade.size(); i++)
		{	
			int dbTabId = dbTabsNeedToUpgrade.get(i);
			String jsonFileName = DBTabNameMap[dbTabId] + ".json";
			String jsonFilePath = docSysIniPath + "backup/";
			exportObjectListToJsonFile(dbTabId, jsonFilePath, jsonFileName, oldVersion, newVersion);

			//copy to backup dir
			copyFile(jsonFilePath + jsonFileName, backUpPath + jsonFileName, true);
		}
		
		if(deleteDBTabs() == false)
		{
			System.out.println("DBUpgrade() 数据库表删除失败!");
			return true;		
		}
		
		if(initDB() == false)
		{
			System.out.println("DBUpgrade() 数据库初始化失败!");
			return false;
		}
		
		for(int i=0; i< dbTabsNeedToUpgrade.size(); i++)
		{
			int dbTabId = dbTabsNeedToUpgrade.get(i);
			String jsonFilePath = docSysIniPath + "backup/";
			String jsonFileName = getNameByObjType(dbTabId) + ".json";
			importObjectListFromJsonFile(dbTabId, jsonFilePath, jsonFileName);
		}
		return true;
	}
	
	private static boolean deleteDBTab(String tabName) {
		boolean ret = false;
		Connection conn = null;
        Statement stmt = null;
        try{
            // 注册 JDBC 驱动
            Class.forName(JDBC_DRIVER);
        
            // 打开链接
            //System.out.println("连接数据库...");
            conn = (Connection) DriverManager.getConnection(DB_URL,DB_USER,DB_PASS);
        
            // 执行查询
            //System.out.println(" 实例化Statement对象...");
            stmt = (Statement) conn.createStatement();
            
            String sql = "DROP TABLE IF EXISTS " + tabName;
            System.out.println("sql:" + sql);
            ret = stmt.execute(sql);
            System.out.println("ret:" + ret);
            // 完成后关闭
            stmt.close();
            conn.close();
            return ret;
        }catch(SQLException se){
            // 处理 JDBC 错误
            se.printStackTrace();
        }catch(Exception e){
            // 处理 Class.forName 错误
            e.printStackTrace();
        }finally{
            // 关闭资源
            try{
                if(stmt!=null) stmt.close();
            }catch(SQLException se2){
            }// 什么都不做
            try{
                if(conn!=null) conn.close();
            }catch(SQLException se){
                se.printStackTrace();
            }
        }
		return ret;
		
	}
	
	private static boolean backupDB(String path, String name) 
	{
		System.out.println("backupDB()");
		
		String backUpContent = "";
		for(int objId=0; objId< DBTabNameMap.length; objId++)
		{
			List<Object> list = dbQuery(null, objId);
			if(list != null)
			{
				for(int i=0; i< list.size(); i++)
				{
					Object obj = list.get(i);
					String sql = buildInsertSqlStr(obj, objId);
					backUpContent += sql + ";\r\n";
				}
				backUpContent += "\r\n";	//换行
			}
		}
		return saveDocContentToFile(backUpContent, path, name);
	}
	
	private static boolean deleteDBTabs() 
	{
		System.out.println("deleteDBTabs()");

		for(int i=0; i< DBTabNameMap.length; i++)
		{
			deleteDBTab(DBTabNameMap[i]);
		}	
		return true;
	}

	private static boolean initDB() 
	{
		System.out.println("initDB()");
		String sqlScriptPath = docSysWebPath + "WEB-INF/classes/docsystem.sql";
		if(isFileExist(sqlScriptPath) == false)
		{
			System.out.println("initDB sqlScriptPath:" + sqlScriptPath + " not exists");
			return false;
		}
		return executeSqlScript("docsystem.sql");
	}
	
	private static List<Integer> getDBTabListForUpgarde(int oldVersion, int newVersion) 
	{
		if(newVersion == oldVersion)
		{
			return null;
		}

		//2.xx.xx版本以下的需要进行数据库升级
		List<Integer> dbTabList = new ArrayList<Integer>();
		for(int i=0; i< DBTabNameMap.length; i++)
		{
//			if(newVersion >= 20000)	//2.xx.xx版本以上DOC和DOC_LOCK是非强制信息
//			{
//				if(DBTabNameMap[i].equals("DOC") || DBTabNameMap[i].equals("DOC_LOCK")) 
//				{
//					continue;
//				}
//			}
			dbTabList.add(i);
		}
		
		return dbTabList;
	}

	private static String getNameByObjType(int objType) {		
		if(objType < DBTabNameMap.length)
		{
			return DBTabNameMap[objType];
		}
		return null;
	}
	
	//exportDocAutListToJsonFile 和 importDocAutListFromJsonFile主要用于实现从1.xx.xx到2.xx.xx的数据库迁移
    //version是指当前数据库对应的软件版本
	protected static void exportObjectListToJsonFile(int objType, String filePath, String fileName, int srcVersion, int dstVersion) 
	{
		System.out.println("exportObjectListToJsonFile() objType:" + objType + " filePath:" + filePath + " srcVersion:" + srcVersion + " dstVersion:" + dstVersion);

		List<Object> list = null;
		if(objType == DOCSYS_DOC_AUTH)
    	{
    		list = queryDocAuth(null, srcVersion, dstVersion);
    	}
		else
		{	
			list = dbQuery(null, objType);
		}
		printObject("exportObjectListToJsonFile() list:", list);
		writeObjectListToJsonFile(objType, list, filePath, fileName);
		System.out.println("exportObjectListToJsonFile() export OK");
	}
	protected static void importObjectListFromJsonFile(int objType, String filePath, String fileName)
	{
		System.out.println("importObjectListFromJsonFile() objType:" + objType + " filePath:" + filePath + " fileName:" + fileName);

		String s = readDocContentFromFile(filePath, fileName, false);
		JSONObject jobj = JSON.parseObject(s);
		
		String name = getNameByObjType(objType);
        JSONArray list = jobj.getJSONArray(name);
        if(list == null || list.size() == 0)
        {
        	System.out.println("importObjectListFromJsonFile() list is empty");
        	return;
        }

        for (int i = 0 ; i < list.size();i++)
        {
            JSONObject jsonObj = (JSONObject)list.get(i);
            
            
            Object obj = buildObjectFromJsonObj(jsonObj, objType);
            
            dbInsert(obj, objType);
        }
    	System.out.println("importObjectListFromJsonFile() import OK");
	}
    
	protected static boolean writeObjectListToJsonFile(int objType, List<Object> list, String filePath, String fileName) 
	{
		String content = JSON.toJSONString(list);
		if(content == null)
		{
			System.out.println("writeObjectListToJsonFile() content is null");
			return false;
		}
				
		System.out.println("writeObjectListToJsonFile() content:" + content);
		
		String name = getNameByObjType(objType);
		content = "{" + name + ":" + content + "}";
			
		return saveDocContentToFile(content, filePath, fileName);
	}

	
	private static Object buildObjectFromJsonObj(JSONObject jsonObj, int objType) {
		switch(objType)
		{
		case DOCSYS_REPOS:
			return buildReposFromJsonObj(jsonObj);
		case DOCSYS_REPOS_AUTH:
			return buildReposAuthFromJsonObj(jsonObj);
		case DOCSYS_DOC:
			return buildDocFromJsonObj(jsonObj);
		case DOCSYS_DOC_AUTH:
			return buildDocAuthFromJsonObj(jsonObj);
		case DOCSYS_DOC_LOCK:
			return buildDocLockFromJsonObj(jsonObj);
		case DOCSYS_USER:
			return buildUserFromJsonObj(jsonObj);		
		case DOCSYS_ROLE:
			return buildRoleFromJsonObj(jsonObj);
		case DOCSYS_USER_GROUP:
			return buildUserGroupFromJsonObj(jsonObj);
		case DOCSYS_GROUP_MEMBER:
			return buildGroupMemberFromJsonObj(jsonObj);
		case DOCSYS_SYS_CONFIG:
			return buildSysConfigFromJsonObj(jsonObj);
		}
		return null;
	}
	
	private static Object createObject(ResultSet rs, int objType) throws Exception {
		switch(objType)
		{
		case DOCSYS_REPOS:
			return buildReposFromResultSet(rs);
		case DOCSYS_REPOS_AUTH:
			return buildReposAuthFromResultSet(rs);
		case DOCSYS_DOC:
			return buildDocFromResultSet(rs);
		case DOCSYS_DOC_AUTH:
			return buildDocAuthFromResultSet(rs);
		case DOCSYS_DOC_LOCK:
			return buildDocLockFromResultSet(rs);
		case DOCSYS_USER:
			return buildUserFromResultSet(rs);		
		case DOCSYS_ROLE:
			return buildRoleFromResultSet(rs);
		case DOCSYS_USER_GROUP:
			return buildUserGroupFromResultSet(rs);
		case DOCSYS_GROUP_MEMBER:
			return buildGroupMemberFromResultSet(rs);
		case DOCSYS_SYS_CONFIG:
			return buildSysConfigFromResultSet(rs);
		}
		return null;
	}
	
	private static List<String> buildParamList(Object qObj, int objType) {
		switch(objType)
		{
		case DOCSYS_REPOS:
			return buildParamListForRepos((Repos) qObj);
		case DOCSYS_REPOS_AUTH:
			return buildParamListForReposAuth((ReposAuth) qObj);
		case DOCSYS_DOC:
			return buildParamListForDoc((Doc) qObj);
		case DOCSYS_DOC_AUTH:
			return buildParamListForDocAuth((DocAuth) qObj);
		case DOCSYS_DOC_LOCK:
			return buildParamListForDocLock((DocLock)qObj);
		case DOCSYS_USER:
			return buildParamListForUser((User) qObj);
		case DOCSYS_ROLE:
			return buildParamListForRole((Role) qObj);
		case DOCSYS_USER_GROUP:
			return buildParamListForUserGroup((UserGroup) qObj);
		case DOCSYS_GROUP_MEMBER:
			return buildParamListForGroupMember((GroupMember) qObj);
		case DOCSYS_SYS_CONFIG:
			return buildParamListForSysConfig((SysConfig) qObj);
		}
		
		return null;
	}

	protected static List<Object> queryDocAuth(DocAuth qDocAuth, int srcVersion, int dstVersion) 
	{
		List<Object> docAuthList = dbQuery(qDocAuth, DOCSYS_DOC_AUTH);
    	
		if(srcVersion != dstVersion &&  srcVersion < 20000 && dstVersion >= 20000) //1.xx.xx版本的docId用的是doc的数据库ID
    	{		
			for(int i=0; i<docAuthList.size(); i++)
	    	{
	    		DocAuth docAuth = (DocAuth) docAuthList.get(i);
	    		
	    		Doc qDoc = new Doc();
	    		qDoc.setVid(docAuth.getReposId());
	    		qDoc.setId(Integer.parseInt(docAuth.getDocId().toString()));
	    		
	    		List<Object> docList = dbQuery(qDoc, DOCSYS_DOC);
	    		if(docList != null && docList.size() == 1)
	    		{
	    			Doc doc = (Doc) docList.get(0);
	    			docAuth.setDocPath(doc.getPath());
	    			docAuth.setDocName(doc.getName());
	    			Long docId = buildDocId(docAuth.getDocPath(), docAuth.getDocName());
	    	        docAuth.setDocId(docId);
	    		}
	    	}
    	}
    	return docAuthList;
	}
	
	protected static List<Object> dbQuery(Object qObj, int objType) 
	{
		List<Object> list = new ArrayList<Object>();
		
        Connection conn = null;
        Statement stmt = null;
        try{
            // 注册 JDBC 驱动
            Class.forName(JDBC_DRIVER);
        
            // 打开链接
            //System.out.println("连接数据库...");
            conn = (Connection) DriverManager.getConnection(DB_URL,DB_USER,DB_PASS);
        
            // 执行查询
            //System.out.println(" 实例化Statement对象...");
            stmt = (Statement) conn.createStatement();
            
            String sql = buildQuerySqlStr(qObj, objType);
    		System.out.println("dbQuery() sql:" + sql);

            ResultSet rs = stmt.executeQuery(sql);
                  
            // 展开结果集数据库
            while(rs.next()){
                Object obj = createObject(rs, objType);
                list.add(obj);
            }
            	
            // 完成后关闭
            rs.close();
            stmt.close();
            conn.close();
            return list;
        }catch(SQLException se){
            // 处理 JDBC 错误
            se.printStackTrace();
        }catch(Exception e){
            // 处理 Class.forName 错误
            e.printStackTrace();
        }finally{
            // 关闭资源
            try{
                if(stmt!=null) stmt.close();
            }catch(SQLException se2){
            }// 什么都不做
            try{
                if(conn!=null) conn.close();
            }catch(SQLException se){
                se.printStackTrace();
            }
        }
		return null;
	}


	public static boolean dbInsert(Object obj, int objType)
	{
		boolean ret = false;
		Connection conn = null;
        Statement stmt = null;
        try{
            // 注册 JDBC 驱动
            Class.forName(JDBC_DRIVER);
        
            // 打开链接
            //System.out.println("连接数据库...");
            conn = (Connection) DriverManager.getConnection(DB_URL,DB_USER,DB_PASS);
        
            // 执行查询
            //System.out.println(" 实例化Statement对象...");
            stmt = (Statement) conn.createStatement();
            
            String sql = buildInsertSqlStr(obj, objType);
            System.out.println("sql:" + sql);
            ret = stmt.execute(sql);
            System.out.println("ret:" + ret);
            // 完成后关闭
            stmt.close();
            conn.close();
            return ret;
        }catch(SQLException se){
            // 处理 JDBC 错误
            se.printStackTrace();
        }catch(Exception e){
            // 处理 Class.forName 错误
            e.printStackTrace();
        }finally{
            // 关闭资源
            try{
                if(stmt!=null) stmt.close();
            }catch(SQLException se2){
            }// 什么都不做
            try{
                if(conn!=null) conn.close();
            }catch(SQLException se){
                se.printStackTrace();
            }
        }
		return ret;
	}

	private static String buildQuerySqlStr(Object qObj, int objType) 
	{
		switch(objType)
		{
		case DOCSYS_REPOS:
			return buildQuerySqlForRepos((Repos) qObj, objType);
		case DOCSYS_REPOS_AUTH:
			return buildQuerySqlForReposAuth((ReposAuth) qObj, objType);
		case DOCSYS_DOC:
			return buildQuerySqlForDoc((Doc) qObj, objType);
		case DOCSYS_DOC_AUTH:
			return buildQuerySqlForDocAuth((DocAuth) qObj, objType);
		case DOCSYS_DOC_LOCK:
			return buildQuerySqlForDocLock((DocLock)qObj, objType);
		case DOCSYS_USER:
			return buildQuerySqlForUser((User) qObj, objType);
		case DOCSYS_ROLE:
			return buildQuerySqlForRole((Role) qObj, objType);
		case DOCSYS_USER_GROUP:
			return buildQuerySqlForUserGroup((UserGroup) qObj, objType);
		case DOCSYS_GROUP_MEMBER:
			return buildQuerySqlForGroupMember((GroupMember) qObj, objType);
		case DOCSYS_SYS_CONFIG:
			return buildQuerySqlForSysConfig((SysConfig) qObj, objType);
		}
		return null;
	}

	private static String buildInsertSqlStr(Object qObj, int objType) {
		switch(objType)
		{
		case DOCSYS_REPOS:
			return buildInsertSqlForRepos((Repos) qObj);
		case DOCSYS_REPOS_AUTH:
			return buildInsertSqlForReposAuth((ReposAuth) qObj);
		case DOCSYS_DOC:
			return buildInsertSqlForDoc((Doc) qObj);
		case DOCSYS_DOC_AUTH:
			return buildInsertSqlForDocAuth((DocAuth) qObj);
		case DOCSYS_DOC_LOCK:
			return buildInsertSqlForDocLock((DocLock)qObj);
		case DOCSYS_USER:
			return buildInsertSqlForUser((User) qObj);
		case DOCSYS_ROLE:
			return buildInsertSqlForRole((Role) qObj);
		case DOCSYS_USER_GROUP:
			return buildInsertSqlForUserGroup((UserGroup) qObj);
		case DOCSYS_GROUP_MEMBER:
			return buildInsertSqlForGroupMember((GroupMember) qObj);
		case DOCSYS_SYS_CONFIG:
			return buildInsertSqlForSysConfig((SysConfig) qObj);
		}
		return null;
	}

	
	private static Object buildSysConfigFromJsonObj(JSONObject jsonObj) {
		SysConfig obj = new SysConfig();
		obj.setId( (Integer)jsonObj.get("id"));
		obj.setRegEnable((Integer)jsonObj.get("regEnable"));
		obj.setPrivateReposEnable( (Integer)jsonObj.get("privateReposEnable"));
		return obj;
	}
	
	private static Object buildSysConfigFromResultSet(ResultSet rs) throws SQLException {
		SysConfig obj = new SysConfig();
		obj.setId( rs.getInt("ID"));
		obj.setRegEnable(rs.getInt("REG_ENABLE"));
		obj.setPrivateReposEnable( rs.getInt("PRIVATE_REPOS_ENABLE"));
		return obj;
	}
	
	private static List<String> buildParamListForSysConfig(SysConfig obj) {
		List<String> paramList = new ArrayList<String>();
		if(obj.getId() != null) { paramList.add("ID");}
		if(obj.getRegEnable() != null) { paramList.add("REG_ENABLE");}
		if(obj.getPrivateReposEnable() != null) { paramList.add("PRIVATE_REPOS_ENABLE");}
		return paramList;
	}
	
	private static String buildInsertSqlForSysConfig(SysConfig obj) {
		if(obj == null)
		{
			return 	null;
		}
		
		String sql_condition = "";
		String sql_value="";
		List<String> paramList = buildParamListForSysConfig(obj);
		int lastParamIndex = paramList.size() - 1;
		for(int i=0; i < paramList.size(); i++)
		{
			String seperator = ",";
			String param = paramList.get(i);
			if(i == lastParamIndex)
			{
				seperator = "";
			}
			sql_condition += param + seperator;	//不带,
			
			switch(param)
			{			
			case "ID": sql_value += " " + obj.getId() + seperator; break;
			case "REG_ENABLE": sql_value += " " + obj.getRegEnable() + seperator; break;
			case "PRIVATE_REPOS_ENABLE": sql_value += " " + obj.getPrivateReposEnable() + seperator; break;
			}
		}
        
        String sql = "insert into SYS_CONFIG (" + sql_condition + ")" + " values (" + sql_value + ")";
        return sql;
	}

	
	private static String buildQuerySqlForSysConfig(SysConfig obj, int objType) {
		String name = getNameByObjType(objType);
		String sql = "select * from " + name;
		
		if(obj == null)
		{
			return 	sql;
		}
		
		List<String> paramList = buildParamList(obj, objType);
		
		if(paramList == null)
		{
			return sql;
		}
		
		String sql_condition = " where ";
		String sql_value="";
		for(int i=0; i < paramList.size(); i++)
		{
			String seperator = " and ";
			String param = paramList.get(i);
			if(i == 0)
			{
				seperator = " ";
			}
			
			switch(param)
			{			
			case "ID": sql_value += seperator + param + "="  + obj.getId(); break;
			case "REG_ENABLE": sql_value += seperator + param + "="  + obj.getRegEnable(); break;
			case "PRIVATE_REPOS_ENABLE": sql_value += seperator + param + "="  + obj.getPrivateReposEnable(); break;
			}
		}
        sql = sql + sql_condition + sql_value;
        return sql;
	}

	private static Object buildGroupMemberFromJsonObj(JSONObject jsonObj) {
		GroupMember obj = new GroupMember();
		obj.setId( (Integer)jsonObj.get("id"));
		obj.setGroupId((Integer)jsonObj.get("groupId"));
		obj.setUserId((Integer)jsonObj.get("userId"));
		return obj;
	}
	
	private static Object buildGroupMemberFromResultSet(ResultSet rs) throws SQLException {
		GroupMember obj = new GroupMember();
		obj.setId( rs.getInt("ID"));
		obj.setGroupId(rs.getInt("GROUP_ID"));
		obj.setUserId(rs.getInt("USER_ID"));
		return obj;
	}

	private static List<String> buildParamListForGroupMember(GroupMember obj) {
		List<String> paramList = new ArrayList<String>();
		if(obj.getId() != null) { paramList.add("ID");}
		if(obj.getGroupId() != null) { paramList.add("GROUP_ID");}
		if(obj.getUserId() != null) { paramList.add("USER_ID");}
		return paramList;
	}
	
	private static String buildInsertSqlForGroupMember(GroupMember obj) {
		if(obj == null)
		{
			return 	null;
		}
		
		String sql_condition = "";
		String sql_value="";
		List<String> paramList = buildParamListForGroupMember(obj);
		int lastParamIndex = paramList.size() - 1;
		for(int i=0; i < paramList.size(); i++)
		{
			String seperator = ",";
			String param = paramList.get(i);
			if(i == lastParamIndex)
			{
				seperator = "";
			}
			sql_condition += param + seperator;	//不带,
			
			switch(param)
			{			
			case "ID": sql_value += " " + obj.getId() + seperator; break;
			case "GROUP_ID": sql_value += " " + obj.getGroupId() + seperator; break;
			case "USER_ID": sql_value += " " + obj.getUserId() + seperator; break;
			}
		}
        
        String sql = "insert into USER (" + sql_condition + ")" + " values (" + sql_value + ")";
        return sql;
	}
	
	
	private static String buildQuerySqlForGroupMember(GroupMember obj, int objType) {
		String name = getNameByObjType(objType);
		String sql = "select * from " + name;
		
		if(obj == null)
		{
			return 	sql;
		}
		
		List<String> paramList = buildParamList(obj, objType);
		
		if(paramList == null)
		{
			return sql;
		}
		
		String sql_condition = " where ";
		String sql_value="";
		for(int i=0; i < paramList.size(); i++)
		{
			String seperator = " and ";
			String param = paramList.get(i);
			if(i == 0)
			{
				seperator = " ";
			}
			
			switch(param)
			{			
			case "ID": sql_value += seperator + param + "="  + obj.getId(); break;
			case "GROUP_ID": sql_value += seperator + param + "="  + obj.getGroupId(); break;
			case "USER_ID": sql_value += seperator + param + "="  + obj.getUserId(); break;
			}
		}
        sql = sql + sql_condition + sql_value;
        return sql;
	}
	
	private static Object buildUserGroupFromJsonObj(JSONObject jsonObj) {
		UserGroup obj = new UserGroup();
		obj.setId( (Integer)jsonObj.get("id"));
		obj.setName((String)jsonObj.get("name"));
		obj.setType((Integer)jsonObj.get("type"));
		obj.setInfo((String)jsonObj.get("info"));
		obj.setImg((String)jsonObj.get("img"));
		obj.setPriority((Integer)jsonObj.get("priority"));
		obj.setCreateTime((String)jsonObj.get("createTime"));
		return obj;
	}
	
	private static Object buildUserGroupFromResultSet(ResultSet rs) throws SQLException {
		UserGroup obj = new UserGroup();
		obj.setId( rs.getInt("ID"));
		obj.setName( rs.getString("NAME"));
		obj.setType( rs.getInt("TYPE"));
		obj.setInfo( rs.getString("INFO"));
		obj.setImg( rs.getString("IMG"));
		obj.setPriority( rs.getInt("PRIORITY"));
		obj.setCreateTime( rs.getString("CREATE_TIME"));
		return obj;
	}

	private static List<String> buildParamListForUserGroup(UserGroup obj) {
		List<String> paramList = new ArrayList<String>();
		if(obj.getId() != null) { paramList.add("ID");}
		if(obj.getName() != null) { paramList.add("NAME");}
		if(obj.getType() != null) { paramList.add("TYPE");}
		if(obj.getInfo() != null) { paramList.add("INFO");}
		if(obj.getImg() != null) { paramList.add("IMG");}
		if(obj.getPriority() != null) { paramList.add("PRIORITY");}
		if(obj.getCreateTime() != null) { paramList.add("CREATE_TIME");}
		return paramList;
	}

	private static String buildInsertSqlForUserGroup(UserGroup obj) {
		if(obj == null)
		{
			return 	null;
		}
		
		String sql_condition = "";
		String sql_value="";
		List<String> paramList = buildParamListForUserGroup(obj);
		int lastParamIndex = paramList.size() - 1;
		for(int i=0; i < paramList.size(); i++)
		{
			String seperator = ",";
			String param = paramList.get(i);
			if(i == lastParamIndex)
			{
				seperator = "";
			}
			sql_condition += param + seperator;	//不带,
			
			switch(param)
			{			
			case "ID": sql_value += " " + obj.getId() + seperator; break;
			case "NAME": sql_value += " '" + obj.getName() + "'" + seperator; break;
			case "TYPE": sql_value += " " + obj.getType() + seperator; break;
			case "INFO": sql_value += " '" + obj.getInfo() + "'" + seperator; break;
			case "IMG": sql_value += " '" + obj.getImg() + "'" + seperator; break;	
			case "PRIORITY": sql_value += " " + obj.getPriority() + seperator; break;
			case "CREATE_TIME": sql_value += " '" + obj.getCreateTime() + "'"  + seperator; break;
			}
		}
        
        String sql = "insert into USER_GROUP (" + sql_condition + ")" + " values (" + sql_value + ")";
        return sql;
	}
	
	private static String buildQuerySqlForUserGroup(UserGroup obj, int objType) {
		String name = getNameByObjType(objType);
		String sql = "select * from " + name;
		
		if(obj == null)
		{
			return 	sql;
		}
		
		List<String> paramList = buildParamList(obj, objType);
		
		if(paramList == null)
		{
			return sql;
		}
		
		String sql_condition = " where ";
		String sql_value="";
		for(int i=0; i < paramList.size(); i++)
		{
			String seperator = " and ";
			String param = paramList.get(i);
			if(i == 0)
			{
				seperator = " ";
			}
			
			switch(param)
			{			
			case "ID": sql_value += seperator + param + "="  + obj.getId(); break;
			case "NAME": sql_value += seperator + param + "='"  + obj.getName() + "'"; break;
			case "TYPE": sql_value += seperator + param + "="  + obj.getType(); break;
			case "INFO": sql_value += seperator + param + "= '"  + obj.getInfo() + "'"; break;
			case "IMG": sql_value += seperator + param + "='"  + obj.getImg() + "'"; break;	
			case "PRIORITY": sql_value += seperator + param + "="  + obj.getPriority(); break;
			case "CREATE_TIME": sql_value += seperator + param + "='"  + obj.getCreateTime() + "'"; break;
			}
		}
        sql = sql + sql_condition + sql_value;
        return sql;
	}
	
	private static Object buildRoleFromJsonObj(JSONObject jsonObj) {
		Role obj = new Role();
		obj.setId( (Integer)jsonObj.get("id"));
		obj.setName((String)jsonObj.get("name"));
		obj.setRoleId((Integer)jsonObj.get("roleId"));
		return obj;
	}
	
	private static Object buildRoleFromResultSet(ResultSet rs) throws SQLException {
		Role obj = new Role();
		obj.setId( rs.getInt("ID"));
		obj.setName( rs.getString("NAME"));
		obj.setRoleId(rs.getInt("ROLE_ID"));
		return obj;
	}
	
	private static List<String> buildParamListForRole(Role obj) {
		List<String> paramList = new ArrayList<String>();
		if(obj.getId() != null) { paramList.add("ID");}
		if(obj.getName() != null) { paramList.add("NAME");}
		if(obj.getRoleId() != null) { paramList.add("ROLE_ID");}
		return paramList;
	}
	
	private static String buildInsertSqlForRole(Role obj) {
		if(obj == null)
		{
			return 	null;
		}
		
		String sql_condition = "";
		String sql_value="";
		List<String> paramList = buildParamListForRole(obj);
		int lastParamIndex = paramList.size() - 1;
		for(int i=0; i < paramList.size(); i++)
		{
			String seperator = ",";
			String param = paramList.get(i);
			if(i == lastParamIndex)
			{
				seperator = "";
			}
			sql_condition += param + seperator;	//不带,
			
			switch(param)
			{			
			case "ID": sql_value += " " + obj.getId() + seperator; break;
			case "NAME": sql_value += " '" + obj.getName()  + "'" + seperator; break;
			case "ROLE_ID": sql_value += " " + obj.getRoleId() + seperator; break;
			}
		}
        
        String sql = "insert into ROLE (" + sql_condition + ")" + " values (" + sql_value + ")";
        return sql;
	}
	
	private static String buildQuerySqlForRole(Role obj, int objType) {
		String name = getNameByObjType(objType);
		String sql = "select * from " + name;
		
		if(obj == null)
		{
			return 	sql;
		}
		
		List<String> paramList = buildParamList(obj, objType);
		
		if(paramList == null)
		{
			return sql;
		}
		
		String sql_condition = " where ";
		String sql_value="";
		for(int i=0; i < paramList.size(); i++)
		{
			String seperator = " and ";
			String param = paramList.get(i);
			if(i == 0)
			{
				seperator = " ";
			}
			
			switch(param)
			{			
			case "ID": sql_value += seperator + param + "="  + obj.getId(); break;
			case "NAME": sql_value += seperator + param + "='"  + obj.getName()  + "'"; break;
			case "ROLE_ID": sql_value += seperator + param + "="  + obj.getRoleId(); break;
			}
		}
        sql = sql + sql_condition + sql_value;
        return sql;
	}

	private static Object buildUserFromJsonObj(JSONObject jsonObj) {
		User obj = new User();
		obj.setId( (Integer)jsonObj.get("id"));
		obj.setName((String)jsonObj.get("name"));
		obj.setPwd((String)jsonObj.get("pwd"));
		obj.setType( (Integer)jsonObj.get("type"));
		obj.setRole( (Integer)jsonObj.get("role"));
		obj.setRealName((String)jsonObj.get("realName"));
		obj.setNickName((String)jsonObj.get("nickName"));
		obj.setIntro((String)jsonObj.get("intro"));
		obj.setImg((String)jsonObj.get("img"));
		obj.setEmail((String)jsonObj.get("email"));
		obj.setEmailValid((Integer)jsonObj.get("emailValid"));
		obj.setTel((String)jsonObj.get("tel"));
		obj.setTelValid((Integer)jsonObj.get("telValid"));
		obj.setLastLoginTime((String)jsonObj.get("lastLoginTime"));
		obj.setLastLoginIp((String)jsonObj.get("lastLoginIp"));
		obj.setLastLoginCity((String)jsonObj.get("lastLoginCity"));
		obj.setCreateType((Integer)jsonObj.get("createType"));
		obj.setCreateTime((String)jsonObj.get("createTime"));
		return obj;
	}
	
	private static Object buildUserFromResultSet(ResultSet rs) throws SQLException {
		User obj = new User();
		obj.setId( rs.getInt("ID"));
		obj.setName(rs.getString("NAME"));
		obj.setPwd(rs.getString("PWD"));
		obj.setType( rs.getInt("TYPE"));
		obj.setRole( rs.getInt("ROLE"));
		obj.setRealName(rs.getString("REAL_NAME"));
		obj.setNickName(rs.getString("NICK_NAME"));
		obj.setIntro(rs.getString("INTRO"));
		obj.setImg(rs.getString("IMG"));
		obj.setEmail(rs.getString("EMAIL"));
		obj.setEmailValid(rs.getInt("EMAIL_VALID"));
		obj.setTel(rs.getString("TEL"));
		obj.setTelValid(rs.getInt("TEL_VALID"));
		obj.setLastLoginTime(rs.getString("LAST_LOGIN_TIME"));
		obj.setLastLoginIp(rs.getString("LAST_LOGIN_IP"));
		obj.setLastLoginCity(rs.getString("LAST_LOGIN_CITY"));
		obj.setCreateType(rs.getInt("CREATE_TYPE"));
		obj.setCreateTime(rs.getString("CREATE_TIME"));
		return obj;
	}
	
	private static List<String> buildParamListForUser(User obj) {
		List<String> paramList = new ArrayList<String>();
		if(obj.getId() != null) { paramList.add("ID");}
		if(obj.getName() != null) { paramList.add("NAME");}
		if(obj.getPwd() != null) { paramList.add("PWD");}
		if(obj.getType() != null) { paramList.add("TYPE");}
		if(obj.getRole() != null) { paramList.add("ROLE");}
		if(obj.getRealName() != null) { paramList.add("REAL_NAME");}
		if(obj.getNickName() != null) { paramList.add("NICK_NAME");}
		if(obj.getIntro() != null) { paramList.add("INTRO");}
		if(obj.getImg() != null) { paramList.add("IMG");}
		if(obj.getEmail() != null) { paramList.add("EMAIL");}
		if(obj.getEmailValid() != null) { paramList.add("EMAIL_VALID");}
		if(obj.getTel() != null) { paramList.add("TEL");}
		if(obj.getTelValid() != null) { paramList.add("TEL_VALID");}
		if(obj.getLastLoginTime() != null) { paramList.add("LAST_LOGIN_TIME");}
		if(obj.getLastLoginIp() != null) { paramList.add("LAST_LOGIN_IP");}
		if(obj.getLastLoginCity() != null) { paramList.add("LAST_LOGIN_CITY");}
		if(obj.getCreateType() != null) { paramList.add("CREATE_TYPE");}
		if(obj.getCreateTime() != null) { paramList.add("CREATE_TIME");}
		return paramList;
	}
	
	private static String buildInsertSqlForUser(User obj) {
		if(obj == null)
		{
			return 	null;
		}
		
		String sql_condition = "";
		String sql_value="";
		List<String> paramList = buildParamListForUser(obj);
		int lastParamIndex = paramList.size() - 1;
		for(int i=0; i < paramList.size(); i++)
		{
			String seperator = ",";
			String param = paramList.get(i);
			if(i == lastParamIndex)
			{
				seperator = "";
			}
			sql_condition += param + seperator;	//不带,
			
			switch(param)
			{			
			case "ID": sql_value += " " + obj.getId() + seperator; break;
			case "NAME": sql_value += " '" + obj.getName() + "'" + seperator; break;
			case "PWD": sql_value += " '" + obj.getPwd()  + "'" + seperator; break;
			case "TYPE": sql_value += " " + obj.getType() + seperator; break;
			case "ROLE": sql_value += " " + obj.getRole() + seperator; break;
			case "REAL_NAME": sql_value += " '" + obj.getRealName() + "'"  + seperator; break;
			case "NICK_NAME": sql_value += " '" + obj.getNickName()  + "'" + seperator; break;
			case "INTRO": sql_value += " '" + obj.getIntro()  + "'" + seperator; break;
			case "IMG": sql_value += " '" + obj.getImg()  + "'" + seperator; break;
			case "EMAIL": sql_value += " '" + obj.getEmail() + "'"  + seperator; break;
			case "EMAIL_VALID": sql_value += " " + obj.getEmailValid() + seperator; break;
			case "TEL": sql_value += " '" + obj.getTel()  + "'" + seperator; break;
			case "TEL_VALID": sql_value += " " + obj.getTelValid() + seperator; break;
			case "LAST_LOGIN_TIME": sql_value += " '" + obj.getLastLoginTime()  + "'" + seperator; break;
			case "LAST_LOGIN_IP": sql_value += " '" + obj.getLastLoginIp()  + "'" + seperator; break;
			case "LAST_LOGIN_CITY": sql_value += " '" + obj.getLastLoginCity()  + "'" + seperator; break;
			case "CREATE_TYPE": sql_value += " " + obj.getCreateType() + seperator; break;				
			case "CREATE_TIME": sql_value += " '" + obj.getCreateTime()  + "'" + seperator; break;
			}
		}
        String sql = "insert into USER (" + sql_condition + ")" + " values (" + sql_value + ")";
        return sql;
	}
	

	private static String buildQuerySqlForUser(User obj, int objType) {
		String name = getNameByObjType(objType);
		String sql = "select * from " + name;
		
		if(obj == null)
		{
			return 	sql;
		}
		
		List<String> paramList = buildParamList(obj, objType);
		
		if(paramList == null)
		{
			return sql;
		}
		
		String sql_condition = " where ";
		String sql_value="";
		for(int i=0; i < paramList.size(); i++)
		{
			String seperator = " and ";
			String param = paramList.get(i);
			if(i == 0)
			{
				seperator = " ";
			}
			
			switch(param)
			{			
			case "ID": sql_value += seperator + param + "="  + obj.getId() ; break;
			case "NAME": sql_value += seperator + param + "='"  + obj.getName()  + "'" ; break;
			case "PWD": sql_value += seperator + param + "='"  + obj.getPwd()  + "'" ; break;
			case "TYPE": sql_value += seperator + param + "="  + obj.getType() ; break;
			case "ROLE": sql_value += seperator + param + "="  + obj.getRole() ; break;
			case "REAL_NAME": sql_value += seperator + param + "='"  + obj.getRealName()  + "'" ; break;
			case "NICK_NAME": sql_value += seperator + param + "='"  + obj.getNickName()  + "'" ; break;
			case "INTRO": sql_value += seperator + param + "='"  + obj.getIntro()  + "'" ; break;
			case "IMG": sql_value += seperator + param + "='"  + obj.getImg()  + "'" ; break;
			case "EMAIL": sql_value += seperator + param + "='"  + obj.getEmail()  + "'" ; break;
			case "EMAIL_VALID": sql_value += seperator + param + "="  + obj.getEmailValid() ; break;
			case "TEL": sql_value += seperator + param + "='"  + obj.getTel()  + "'" ; break;
			case "TEL_VALID": sql_value += seperator + param + "="  + obj.getTelValid() ; break;
			case "LAST_LOGIN_TIME": sql_value += seperator + param + "='"  + obj.getLastLoginTime()  + "'" ; break;
			case "LAST_LOGIN_IP": sql_value += seperator + param + "='"  + obj.getLastLoginIp()  + "'" ; break;
			case "LAST_LOGIN_CITY": sql_value += seperator + param + "='"  + obj.getLastLoginCity()  + "'" ; break;
			case "CREATE_TYPE": sql_value += seperator + param + "="  + obj.getCreateType() ; break;				
			case "CREATE_TIME": sql_value += seperator + param + "='"  + obj.getCreateTime()  + "'" ; break;
			}
		}
        sql = sql + sql_condition + sql_value;
        return sql;
	}

	private static Object buildReposFromJsonObj(JSONObject jsonObj) {
		Repos obj = new Repos();
		obj.setId( (Integer)jsonObj.get("id"));
		obj.setName( (String)jsonObj.get("name"));
		obj.setType( (Integer)jsonObj.get("type"));
		obj.setPath( (String)jsonObj.get("path"));
		obj.setRealDocPath( (String)jsonObj.get("realDocPath"));
		obj.setVerCtrl((Integer)jsonObj.get("verCtrl"));
		obj.setIsRemote( (Integer)jsonObj.get("isRemote"));
		obj.setLocalSvnPath( (String)jsonObj.get("localSvnPath"));
		obj.setSvnPath( (String)jsonObj.get("svnPath"));
		obj.setSvnUser( (String)jsonObj.get("svnUser"));		
		obj.setSvnPwd( (String)jsonObj.get("svnPwd"));		
		obj.setRevision( (String)jsonObj.get("revision"));		
		obj.setVerCtrl1((Integer)jsonObj.get("verCtrl1"));
		obj.setIsRemote1( (Integer)jsonObj.get("isRemote1"));
		obj.setLocalSvnPath1( (String)jsonObj.get("localSvnPath1"));
		obj.setSvnPath1( (String)jsonObj.get("svnPath1"));
		obj.setSvnUser1( (String)jsonObj.get("svnUser1"));		
		obj.setSvnPwd1( (String)jsonObj.get("svnPwd1"));		
		obj.setRevision1( (String)jsonObj.get("revision1"));
		obj.setPwd( (String)jsonObj.get("pwd"));
		obj.setOwner( (Integer)jsonObj.get("owner"));
		obj.setCreateTime(Long.parseLong(jsonObj.get("createTime").toString()));
		obj.setState( (Integer) jsonObj.get("state"));
		obj.setLockBy( (Integer)jsonObj.get("lockBy"));
		obj.setLockTime(Long.parseLong(jsonObj.get("lockTime").toString()));
		return obj;
	}
	
	private static Object buildReposFromResultSet(ResultSet rs) throws SQLException {
		Repos obj = new Repos();
		obj.setId( rs.getInt("ID"));
		obj.setName( rs.getString("NAME"));
		obj.setType( rs.getInt("TYPE"));
		obj.setPath( rs.getString("PATH"));
		obj.setRealDocPath( rs.getString("REAL_DOC_PATH"));
		obj.setVerCtrl(rs.getInt("VER_CTRL"));
		obj.setIsRemote( rs.getInt("IS_REMOTE"));
		obj.setLocalSvnPath( rs.getString("LOCAL_SVN_PATH"));
		obj.setSvnPath( rs.getString("SVN_PATH"));
		obj.setSvnUser( rs.getString("SVN_USER"));		
		obj.setSvnPwd( rs.getString("SVN_PWD"));		
		obj.setRevision( rs.getString("REVISION"));		
		obj.setVerCtrl1(rs.getInt("VER_CTRL1"));
		obj.setIsRemote1( rs.getInt("IS_REMOTE1"));
		obj.setLocalSvnPath1( rs.getString("LOCAL_SVN_PATH1"));
		obj.setSvnPath1( rs.getString("SVN_PATH1"));
		obj.setSvnUser1( rs.getString("SVN_USER1"));		
		obj.setSvnPwd1( rs.getString("SVN_PWD1"));		
		obj.setRevision1( rs.getString("REVISION1"));
		obj.setPwd( rs.getString("PWD"));
		obj.setOwner( rs.getInt("OWNER"));
		obj.setCreateTime(rs.getLong("CREATE_TIME"));
		obj.setState( rs.getInt("STATE"));
		obj.setLockBy( rs.getInt("LOCK_BY"));
		obj.setLockTime(rs.getLong("LOCK_TIME"));
		return obj;
	}
	
	private static List<String> buildParamListForRepos(Repos obj) {
		List<String> paramList = new ArrayList<String>();
		if(obj.getId( ) != null) { paramList.add("ID");}
		if(obj.getName( ) != null) { paramList.add("NAME");}
		if(obj.getType( ) != null) { paramList.add("TYPE");}
		if(obj.getPath( ) != null) { paramList.add("PATH");}
		if(obj.getRealDocPath( ) != null) { paramList.add("REAL_DOC_PATH");}
		if(obj.getVerCtrl() != null) { paramList.add("VER_CTRL");}
		if(obj.getIsRemote( ) != null) { paramList.add("IS_REMOTE");}
		if(obj.getLocalSvnPath( ) != null) { paramList.add("LOCAL_SVN_PATH");}
		if(obj.getSvnPath( ) != null) { paramList.add("SVN_PATH");}
		if(obj.getSvnUser( ) != null) { paramList.add("SVN_USER");}		
		if(obj.getSvnPwd( ) != null) { paramList.add("SVN_PWD");}		
		if(obj.getRevision( ) != null) { paramList.add("REVISION");}		
		if(obj.getVerCtrl1() != null) { paramList.add("VER_CTRL1");}
		if(obj.getIsRemote1( ) != null) { paramList.add("IS_REMOTE1");}
		if(obj.getLocalSvnPath1( ) != null) { paramList.add("LOCAL_SVN_PATH1");}
		if(obj.getSvnPath1( ) != null) { paramList.add("SVN_PATH1");}
		if(obj.getSvnUser1( ) != null) { paramList.add("SVN_USER1");}		
		if(obj.getSvnPwd1( ) != null) { paramList.add("SVN_PWD1");}		
		if(obj.getRevision1( ) != null) { paramList.add("REVISION1");}
		if(obj.getPwd( ) != null) { paramList.add("PWD");}
		if(obj.getOwner( ) != null) { paramList.add("OWNER");}
		if(obj.getCreateTime() != null) { paramList.add("CREATE_TIME");}
		if(obj.getState( ) != null) { paramList.add("STATE");}
		if(obj.getLockBy( ) != null) { paramList.add("LOCK_BY");}
		if(obj.getLockTime() != null) { paramList.add("LOCK_TIME");}
		return paramList;
	}
	
	private static String buildInsertSqlForRepos(Repos obj) {
		if(obj == null)
		{
			return 	null;
		}
		
		String sql_condition = "";
		String sql_value="";
		List<String> paramList = buildParamListForRepos(obj);
		int lastParamIndex = paramList.size() - 1;
		for(int i=0; i < paramList.size(); i++)
		{
			String seperator = ",";
			String param = paramList.get(i);
			if(i == lastParamIndex)
			{
				seperator = "";
			}
			sql_condition += param + seperator;	//不带,
			
			switch(param)
			{			
			case "ID": sql_value += " " + obj.getId() + seperator; break;
			case "NAME": sql_value += " '" + obj.getName()  + "'" + seperator; break;
			case "TYPE": sql_value += " " + obj.getType() + seperator; break;
			case "PATH": sql_value += " '" + obj.getPath()  + "'" + seperator; break;
			case "REAL_DOC_PATH": sql_value += " '" + obj.getRealDocPath()  + "'" + seperator; break;
			case "VER_CTRL": sql_value += " " + obj.getVerCtrl() + seperator; break;
			case "IS_REMOTE": sql_value += " " + obj.getIsRemote() + seperator; break;
			case "LOCAL_SVN_PATH": sql_value += " '" + obj.getLocalSvnPath()  + "'" + seperator; break;
			case "SVN_PATH": sql_value += " '" + obj.getSvnPath() + "'"  + seperator; break;
			case "SVN_USER": sql_value += " '" + obj.getSvnUser() + "'"  + seperator; break;
			case "SVN_PWD": sql_value += " '" + obj.getSvnPwd() + "'"  + seperator; break;
			case "REVISION": sql_value += " '" + obj.getRevision() + "'"  + seperator; break;
			case "VER_CTRL1": sql_value += " " + obj.getVerCtrl1() + seperator; break;
			case "IS_REMOTE1": sql_value += " " + obj.getIsRemote1() + seperator; break;
			case "LOCAL_SVN_PATH1": sql_value += " '" + obj.getLocalSvnPath1() + "'"  + seperator; break;
			case "SVN_PATH1": sql_value += " '" + obj.getSvnPath1() + "'"  + seperator; break;
			case "SVN_USER1": sql_value += " '" + obj.getSvnUser1() + "'"  + seperator; break;
			case "SVN_PWD1": sql_value += " '" + obj.getSvnPwd1() + "'"  + seperator; break;
			case "REVISION1": sql_value += " '" + obj.getRevision1() + "'"  + seperator; break;
			case "PWD": sql_value += " '" + obj.getPwd() + "'"  + seperator; break;
			case "OWNER": sql_value += " " + obj.getOwner() + seperator; break;
			case "CREATE_TIME": sql_value += " " + obj.getCreateTime() + seperator; break;
			case "STATE": sql_value += " " + obj.getState() + seperator; break;
			case "LOCK_BY": sql_value += " " + obj.getLockBy() + seperator; break;
			case "LOCK_TIME": sql_value += " " + obj.getLockTime() + seperator; break;
			}
		}
        String sql = "insert into REPOS (" + sql_condition + ")" + " values (" + sql_value + ")";
        return sql;
	}
	
	private static String buildQuerySqlForRepos(Repos obj, int objType) {
		String name = getNameByObjType(objType);
		String sql = "select * from " + name;
		
		if(obj == null)
		{
			return 	sql;
		}
		
		List<String> paramList = buildParamList(obj, objType);
		
		if(paramList == null)
		{
			return sql;
		}
		
		String sql_condition = " where ";
		String sql_value="";
		for(int i=0; i < paramList.size(); i++)
		{
			String seperator = " and ";
			String param = paramList.get(i);
			if(i == 0)
			{
				seperator = " ";
			}
			
			switch(param)
			{			
			case "ID": sql_value += seperator + param + "="  + obj.getId() ; break;
			case "NAME": sql_value += seperator + param + "='"  + obj.getName() + "'"; break;
			case "TYPE": sql_value += seperator + param + "="  + obj.getType(); break;
			case "PATH": sql_value += seperator + param + "='"  + obj.getPath() + "'"  ; break;
			case "REAL_DOC_PATH": sql_value += seperator + param + "='"  + obj.getRealDocPath() + "'"  ; break;
			case "VER_CTRL": sql_value += seperator + param + "="  + obj.getVerCtrl() ; break;
			case "IS_REMOTE": sql_value += seperator + param + "="  + obj.getIsRemote() ; break;
			case "LOCAL_SVN_PATH": sql_value += seperator + param + "='"  + obj.getLocalSvnPath() + "'"  ; break;
			case "SVN_PATH": sql_value += seperator + param + "='"  + obj.getSvnPath() + "'"  ; break;
			case "SVN_USER": sql_value += seperator + param + "'="  + obj.getSvnUser() + "'"  ; break;
			case "SVN_PWD": sql_value += seperator + param + "='"  + obj.getSvnPwd() + "'"  ; break;
			case "REVISION": sql_value += seperator + param + "='"  + obj.getRevision() + "'"  ; break;
			case "VER_CTRL1": sql_value += seperator + param + "="  + obj.getVerCtrl1() ; break;
			case "IS_REMOTE1": sql_value += seperator + param + "="  + obj.getIsRemote1() ; break;
			case "LOCAL_SVN_PATH1": sql_value += seperator + param + "='"  + obj.getLocalSvnPath1() + "'"  ; break;
			case "SVN_PATH1": sql_value += seperator + param + "='"  + obj.getSvnPath1() + "'"  ; break;
			case "SVN_USER1": sql_value += seperator + param + "='"  + obj.getSvnUser1() + "'"  ; break;
			case "SVN_PWD1": sql_value += seperator + param + "='"  + obj.getSvnPwd1() + "'"  ; break;
			case "REVISION1": sql_value += seperator + param + "='"  + obj.getRevision1() + "'"  ; break;
			case "PWD": sql_value += seperator + param + "="  + obj.getPwd() + "'"  ; break;
			case "OWNER": sql_value += seperator + param + "="  + obj.getOwner() ; break;
			case "CREATE_TIME": sql_value += seperator + param + "="  + obj.getCreateTime() ; break;
			case "STATE": sql_value += seperator + param + "="  + obj.getState() ; break;
			case "LOCK_BY": sql_value += seperator + param + "="  + obj.getLockBy() ; break;
			case "LOCK_TIME": sql_value += seperator + param + "="  + obj.getLockTime() ; break;
			}
		}
        sql = sql + sql_condition + sql_value;
        return sql;
	}
	
	private static Object buildReposAuthFromJsonObj(JSONObject jsonObj) {
		ReposAuth obj = new ReposAuth();
		obj.setId( (Integer)jsonObj.get("id"));
		obj.setReposId( (Integer)jsonObj.get("reposId"));
		obj.setUserId( (Integer)jsonObj.get("userId"));
		obj.setGroupId( (Integer)jsonObj.get("groupId"));
		obj.setType( (Integer)jsonObj.get("type"));
		obj.setPriority( (Integer)jsonObj.get("priority"));
		obj.setIsAdmin( (Integer)jsonObj.get("isAdmin"));
		obj.setAddEn( (Integer)jsonObj.get("addEn"));
		obj.setDeleteEn( (Integer)jsonObj.get("deleteEn"));
		obj.setEditEn( (Integer)jsonObj.get("editEn"));
		obj.setAccess( (Integer)jsonObj.get("access"));
		obj.setHeritable( (Integer)jsonObj.get("heritable"));
		return obj;
	}

	private static Object buildReposAuthFromResultSet(ResultSet rs) throws SQLException {
		ReposAuth obj = new ReposAuth();
		obj.setId( rs.getInt("ID"));
		obj.setReposId( rs.getInt("REPOS_ID"));
		obj.setUserId( rs.getInt("USER_ID"));
		obj.setGroupId( rs.getInt("GROUP_ID"));
		obj.setType( rs.getInt("TYPE"));
		obj.setPriority( rs.getInt("PRIORITY"));
		obj.setIsAdmin( rs.getInt("IS_ADMIN"));
		obj.setAddEn( rs.getInt("ADD_EN"));
		obj.setDeleteEn( rs.getInt("DELETE_EN"));
		obj.setEditEn( rs.getInt("EDIT_EN"));
		obj.setAccess( rs.getInt("ACCESS"));
		obj.setHeritable( rs.getInt("HERITABLE"));
		return obj;
	}

	private static List<String> buildParamListForReposAuth(ReposAuth obj) {
		List<String> paramList = new ArrayList<String>();
		if(obj.getId( ) != null) { paramList.add("ID");}
		if(obj.getReposId( ) != null) { paramList.add("REPOS_ID");}
		if(obj.getUserId( ) != null) { paramList.add("USER_ID");}
		if(obj.getGroupId( ) != null) { paramList.add("GROUP_ID");}
		if(obj.getType( ) != null) { paramList.add("TYPE");}
		if(obj.getPriority( ) != null) { paramList.add("PRIORITY");}
		if(obj.getIsAdmin( ) != null) { paramList.add("IS_ADMIN");}
		if(obj.getAddEn( ) != null) { paramList.add("ADD_EN");}
		if(obj.getDeleteEn( ) != null) { paramList.add("DELETE_EN");}
		if(obj.getEditEn( ) != null) { paramList.add("EDIT_EN");}
		if(obj.getAccess( ) != null) { paramList.add("ACCESS");}
		if(obj.getHeritable( ) != null) { paramList.add("HERITABLE");}
		return paramList;
	}

	private static String buildInsertSqlForReposAuth(ReposAuth obj) {
		if(obj == null)
		{
			return 	null;
		}
		
		String sql_condition = "";
		String sql_value="";
		List<String> paramList = buildParamListForReposAuth(obj);
		int lastParamIndex = paramList.size() - 1;
		for(int i=0; i < paramList.size(); i++)
		{
			String seperator = ",";
			String param = paramList.get(i);
			if(i == lastParamIndex)
			{
				seperator = "";
			}
			sql_condition += param + seperator;	//不带,
			
			switch(param)
			{			
			case "ID": sql_value += " " + obj.getId() + seperator; break;
			case "REPOS_ID": sql_value += " " + obj.getReposId() + seperator; break;
			case "USER_ID": sql_value += " " + obj.getUserId() + seperator; break;
			case "GROUP_ID": sql_value += " " + obj.getGroupId() + seperator; break;
			case "TYPE": sql_value += " " + obj.getType() + seperator; break;
			case "PRIORITY": sql_value += " " + obj.getPriority() + seperator; break;
			case "IS_ADMIN": sql_value += " " + obj.getIsAdmin() + seperator; break;
			case "ADD_EN": sql_value += " " + obj.getAddEn() + seperator; break;
			case "DELETE_EN": sql_value += " " + obj.getDeleteEn() + seperator; break;
			case "EDIT_EN": sql_value += " " + obj.getEditEn() + seperator; break;
			case "ACCESS": sql_value += " " + obj.getAccess() + seperator; break;
			case "HERITABLE": sql_value += " " + obj.getHeritable() + seperator; break;
			}
		}
        String sql = "insert into REPOS_AUTH (" + sql_condition + ")" + " values (" + sql_value + ")";
        return sql;
	}
	

	private static String buildQuerySqlForReposAuth(ReposAuth obj, int objType) {
		String name = getNameByObjType(objType);
		String sql = "select * from " + name;
		
		if(obj == null)
		{
			return 	sql;
		}
		
		List<String> paramList = buildParamList(obj, objType);
		
		if(paramList == null)
		{
			return sql;
		}
		
		String sql_condition = " where ";
		String sql_value="";
		for(int i=0; i < paramList.size(); i++)
		{
			String seperator = " and ";
			String param = paramList.get(i);
			if(i == 0)
			{
				seperator = " ";
			}
			
			switch(param)
			{			
			case "ID": sql_value += seperator + param + "="  + obj.getId() ; break;
			case "REPOS_ID": sql_value += seperator + param + "="  + obj.getReposId() ; break;
			case "USER_ID": sql_value += seperator + param + "="  + obj.getUserId() ; break;
			case "GROUP_ID": sql_value += seperator + param + "="  + obj.getGroupId() ; break;
			case "TYPE": sql_value += seperator + param + "="  + obj.getType() ; break;
			case "PRIORITY": sql_value += seperator + param + "="  + obj.getPriority() ; break;
			case "IS_ADMIN": sql_value += seperator + param + "="  + obj.getIsAdmin() ; break;
			case "ADD_EN": sql_value += seperator + param + "="  + obj.getAddEn() ; break;
			case "DELETE_EN": sql_value += seperator + param + "="  + obj.getDeleteEn() ; break;
			case "EDIT_EN": sql_value += seperator + param + "="  + obj.getEditEn() ; break;
			case "ACCESS": sql_value += seperator + param + "="  + obj.getAccess() ; break;
			case "HERITABLE": sql_value += seperator + param + "="  + obj.getHeritable() ; break;
			}
		}
        sql = sql + sql_condition + sql_value;
        return sql;
	}

	private static Doc buildDocFromJsonObj(JSONObject jsonObj) {
		Doc obj = new Doc();
		obj.setId( (Integer)jsonObj.get("id"));
		obj.setName( (String)jsonObj.get("name"));
		obj.setType( (Integer)jsonObj.get("type"));
		obj.setSize( Long.parseLong(jsonObj.get("size").toString()));
        obj.setCheckSum((String)jsonObj.get("checkSum"));
        obj.setRevision((String)jsonObj.get("revision"));
        obj.setContent((String)jsonObj.get("content"));
        obj.setPath( (String)jsonObj.get("path"));
        obj.setDocId( Long.parseLong(jsonObj.get("docId").toString()));
        obj.setPid( Long.parseLong(jsonObj.get("pid").toString()));
        obj.setVid( (Integer)jsonObj.get("vid"));
        obj.setPwd( (String)jsonObj.get("pwd"));
        obj.setCreator( (Integer)jsonObj.get("creator"));
        obj.setCreateTime( Long.parseLong(jsonObj.get("createTime").toString()));
        obj.setLatestEditor( (Integer)jsonObj.get("latestEditor"));
        obj.setLatestEditTime( Long.parseLong(jsonObj.get("latestEditTime").toString()));
        return obj;
	}
	
	private static Doc buildDocFromResultSet(ResultSet rs) throws Exception {
		Doc obj = new Doc();
		obj.setId( rs.getInt("ID"));
		obj.setName( rs.getString("NAME"));
		obj.setType( rs.getInt("TYPE"));
		obj.setSize( rs.getLong("SIZE"));
        obj.setCheckSum(rs.getString("CHECK_SUM"));
        obj.setRevision(rs.getString("REVISION"));
        obj.setContent(rs.getString("CONTENT"));
        obj.setPath( rs.getString("PATH"));
        obj.setDocId( rs.getLong("DOC_ID"));
        obj.setPid( rs.getLong("PID"));
        obj.setVid( rs.getInt("VID"));
        obj.setPwd( rs.getString("PWD"));
        obj.setCreator( rs.getInt("CREATOR"));
        obj.setCreateTime( rs.getLong("CREATE_TIME"));
        obj.setLatestEditor( rs.getInt("LATEST_EDITOR"));
        obj.setLatestEditTime( rs.getLong("LATEST_EDIT_TIME"));
        return obj;        
	}
	
	private static List<String> buildParamListForDoc(Doc obj) {
		List<String> paramList = new ArrayList<String>();
		if(obj.getId( ) != null) { paramList.add("ID");}
		if(obj.getName( ) != null) { paramList.add("NAME");}
		if(obj.getType( ) != null) { paramList.add("TYPE");}
		if(obj.getSize( ) != null) { paramList.add("SIZE");}
        if(obj.getCheckSum() != null) { paramList.add("CHECK_SUM");}
        if(obj.getRevision() != null) { paramList.add("REVISION");}
        if(obj.getContent() != null) { paramList.add("CONTENT");}
        if(obj.getPath( ) != null) { paramList.add("PATH");}
        if(obj.getDocId( ) != null) { paramList.add("DOC_ID");}
        if(obj.getPid( ) != null) { paramList.add("PID");}
        if(obj.getVid( ) != null) { paramList.add("VID");}
        if(obj.getPwd( ) != null) { paramList.add("PWD");}
        if(obj.getCreator( ) != null) { paramList.add("CREATOR");}
        if(obj.getCreateTime( ) != null) { paramList.add("CREATE_TIME");}
        if(obj.getLatestEditor( ) != null) { paramList.add("LATEST_EDITOR");}
        if(obj.getLatestEditTime( ) != null) { paramList.add("LATEST_EDIT_TIME");}
        return paramList;
	}

	private static String buildInsertSqlForDoc(Doc obj) {
		if(obj == null)
		{
			return 	null;
		}
		
		String sql_condition = "";
		String sql_value="";
		List<String> paramList = buildParamListForDoc(obj);
		int lastParamIndex = paramList.size() - 1;
		for(int i=0; i < paramList.size(); i++)
		{
			String seperator = ",";
			String param = paramList.get(i);
			if(i == lastParamIndex)
			{
				seperator = "";
			}
			sql_condition += param + seperator;	//不带,
			
			switch(param)
			{			
			case "ID": sql_value += " " + obj.getId() + seperator; break;
			case "NAME": sql_value += " '" + obj.getName()  + "'"  + seperator; break;
			case "TYPE": sql_value += " " + obj.getType() + seperator; break;
			case "SIZE": sql_value += " " + obj.getSize() + seperator; break;
			case "CHECK_SUM": sql_value += " '" + obj.getCheckSum() + "'"  + seperator; break;
			case "REVISION": sql_value += " '" + obj.getRevision() + "'"  + seperator; break;
			case "CONTENT": sql_value += " '" + obj.getContent() + "'"  + seperator; break;
			case "PATH": sql_value += " '" + obj.getPath() + "'"  + seperator; break;
			case "DOC_ID": sql_value += " " + obj.getDocId() + seperator; break;
			case "PID": sql_value += " " + obj.getPid() + seperator; break;
			case "VID": sql_value += " " + obj.getVid() + seperator; break;
			case "PWD": sql_value += " '" + obj.getPwd() + "'"  + seperator; break;
			case "CREATOR": sql_value += " " + obj.getCreator() + seperator; break;
			case "CREATE_TIME": sql_value += " " + obj.getCreateTime() + seperator; break;
			case "LATEST_EDITOR": sql_value += " " + obj.getLatestEditor() + seperator; break;
			case "LATEST_EDIT_TIME": sql_value += " " + obj.getLatestEditTime() + seperator; break;
			}
		}
        String sql = "insert into DOC (" + sql_condition + ")" + " values (" + sql_value + ")";
        return sql;
	}
	

	private static String buildQuerySqlForDoc(Doc obj, int objType) {
		String name = getNameByObjType(objType);
		String sql = "select * from " + name;
		
		if(obj == null)
		{
			return 	sql;
		}
		
		List<String> paramList = buildParamList(obj, objType);
		
		if(paramList == null)
		{
			return sql;
		}
		
		String sql_condition = " where ";
		String sql_value="";
		for(int i=0; i < paramList.size(); i++)
		{
			String seperator = " and ";
			String param = paramList.get(i);
			if(i == 0)
			{
				seperator = " ";
			}
			
			switch(param)
			{			
			case "ID": sql_value += seperator + param + "="  + obj.getId(); break;
			case "NAME": sql_value += seperator + param + "='"  + obj.getName() + "'" ; break;
			case "TYPE": sql_value += seperator + param + "="  + obj.getType(); break;
			case "SIZE": sql_value += seperator + param + "="  + obj.getSize(); break;
			case "CHECK_SUM": sql_value += seperator + param + "='"  + obj.getCheckSum() + "'" ; break;
			case "REVISION": sql_value += seperator + param + "='"  + obj.getRevision() + "'" ; break;
			case "CONTENT": sql_value += seperator + param + "='"  + obj.getContent() + "'" ; break;
			case "PATH": sql_value += seperator + param + "='"  + obj.getPath() + "'" ; break;
			case "DOC_ID": sql_value += seperator + param + "="  + obj.getDocId(); break;
			case "PID": sql_value += seperator + param + "="  + obj.getPid(); break;
			case "VID": sql_value += seperator + param + "="  + obj.getVid(); break;
			case "PWD": sql_value += seperator + param + "='"  + obj.getPwd() + "'" ; break;
			case "CREATOR": sql_value += seperator + param + "="  + obj.getCreator(); break;
			case "CREATE_TIME": sql_value += seperator + param + "="  + obj.getCreateTime(); break;
			case "LATEST_EDITOR": sql_value += seperator + param + "="  + obj.getLatestEditor(); break;
			case "LATEST_EDIT_TIME": sql_value += seperator + param + "="  + obj.getLatestEditTime(); break;
			}
		}
        sql = sql + sql_condition + sql_value;
        return sql;
	}

	private static DocAuth buildDocAuthFromJsonObj(JSONObject jsonObj) {
		DocAuth obj = new DocAuth();
		obj.setId( (Integer)jsonObj.get("id"));
		obj.setReposId( (Integer)jsonObj.get("reposId"));
		obj.setDocId( Long.parseLong(jsonObj.get("docId").toString()));            	
		obj.setUserId( (Integer)jsonObj.get("userId"));
		obj.setGroupId( (Integer)jsonObj.get("groupId"));
		obj.setType( (Integer)jsonObj.get("type"));
		obj.setPriority( (Integer)jsonObj.get("priority"));
		obj.setIsAdmin( (Integer)jsonObj.get("isAdmin"));
		obj.setAddEn( (Integer)jsonObj.get("addEn"));
		obj.setDeleteEn( (Integer)jsonObj.get("deleteEn"));
		obj.setEditEn( (Integer)jsonObj.get("editEn"));
		obj.setAccess( (Integer)jsonObj.get("access"));
		obj.setHeritable( (Integer)jsonObj.get("heritable"));
		obj.setDocPath( (String)jsonObj.get("docPath"));
		obj.setDocName( (String)jsonObj.get("docName"));
		return obj;
	}

	private static DocAuth buildDocAuthFromResultSet(ResultSet rs) throws Exception {
        DocAuth obj = new DocAuth();
        obj.setId(rs.getInt("ID"));
        obj.setReposId(rs.getInt("REPOS_ID"));
        obj.setDocId(rs.getLong("DOC_ID"));
        obj.setType(rs.getInt("TYPE"));
        obj.setPriority(rs.getInt("PRIORITY"));
        obj.setUserId(rs.getInt("USER_ID"));
        obj.setGroupId(rs.getInt("GROUP_ID"));
        obj.setIsAdmin(rs.getInt("IS_ADMIN"));
        obj.setAccess(rs.getInt("ACCESS"));
        obj.setEditEn(rs.getInt("EDIT_EN"));
        obj.setAddEn(rs.getInt("ADD_EN"));
        obj.setDeleteEn(rs.getInt("DELETE_EN"));
        obj.setHeritable(rs.getInt("HERITABLE"));
        obj.setDocPath(rs.getString("DOC_PATH"));
        obj.setDocName(rs.getString("DOC_NAME"));
		return obj;
	}
	
	private static List<String> buildParamListForDocAuth(DocAuth obj) 
	{
		List<String> paramList = new ArrayList<String>();
        if(obj.getId() != null) { paramList.add("ID");}
        if(obj.getReposId() != null) { paramList.add("REPOS_ID");}
        if(obj.getDocId() != null) { paramList.add("DOC_ID");}
        if(obj.getType() != null) { paramList.add("TYPE");}
        if(obj.getPriority() != null) { paramList.add("PRIORITY");}
        if(obj.getUserId() != null) { paramList.add("USER_ID");}
        if(obj.getGroupId() != null) { paramList.add("GROUP_ID");}
        if(obj.getIsAdmin() != null) { paramList.add("IS_ADMIN");}
        if(obj.getAccess() != null) { paramList.add("ACCESS");}
        if(obj.getEditEn() != null) { paramList.add("EDIT_EN");}
        if(obj.getAddEn() != null) { paramList.add("ADD_EN");}
        if(obj.getDeleteEn() != null) { paramList.add("DELETE_EN");}
        if(obj.getHeritable() != null) { paramList.add("HERITABLE");}
        if(obj.getDocPath() != null) { paramList.add("DOC_PATH");}
        if(obj.getDocName() != null) { paramList.add("DOC_NAME");}
        return paramList;
	}
	

	private static String buildInsertSqlForDocAuth(DocAuth obj) 
	{
		if(obj == null)
		{
			return 	null;
		}
		
		String sql_condition = "";
		String sql_value="";
		List<String> paramList = buildParamListForDocAuth(obj);
		int lastParamIndex = paramList.size() - 1;
		for(int i=0; i < paramList.size(); i++)
		{
			String seperator = ",";
			String param = paramList.get(i);
			if(i == lastParamIndex)
			{
				seperator = "";
			}
			sql_condition += param + seperator;	//不带,
			
			switch(param)
			{
			case "ID": sql_value += " " + obj.getId() + seperator; break;
			case "USER_ID": sql_value += " " + obj.getUserId() + seperator; break;
			case "GROUP_ID": sql_value += " " + obj.getGroupId() + seperator; break;
			case "TYPE": sql_value += " " + obj.getType() + seperator; break;
			case "PRIORITY": sql_value += " " + obj.getPriority() + seperator; break;
			case "DOC_ID": sql_value += " " + obj.getDocId() + seperator; break;
			case "REPOS_ID": sql_value += " " + obj.getReposId() + seperator; break;
			case "IS_ADMIN": sql_value += " " + obj.getIsAdmin() + seperator; break;	
			case "ACCESS": sql_value += " " + obj.getAccess() + seperator; break;
        	case "EDIT_EN": sql_value += " " + obj.getEditEn() + seperator; break;
        	case "ADD_EN": sql_value += " " + obj.getAddEn() + seperator; break;
        	case "DELETE_EN": sql_value += " " + obj.getDeleteEn() + seperator; break;
        	case "HERITABLE": sql_value += " " + obj.getHeritable() + seperator; break;
        	case "DOC_PATH": sql_value += " '" + obj.getDocPath() + "'"  + seperator; break;
        	case "DOC_NAME": sql_value += " '" + obj.getDocName() + "'"  + seperator; break;
			}
		}
        
        String sql = "insert into doc_auth (" + sql_condition + ")" + " values (" + sql_value + ")";
        return sql;
	}
	

	private static String buildQuerySqlForDocAuth(DocAuth obj, int objType) {
		String name = getNameByObjType(objType);
		String sql = "select * from " + name;
		
		if(obj == null)
		{
			return 	sql;
		}
		
		List<String> paramList = buildParamList(obj, objType);
		
		if(paramList == null)
		{
			return sql;
		}
		
		String sql_condition = " where ";
		String sql_value="";
		for(int i=0; i < paramList.size(); i++)
		{
			String seperator = " and ";
			String param = paramList.get(i);
			if(i == 0)
			{
				seperator = " ";
			}
			
			switch(param)
			{			
			case "ID": sql_value += seperator + param + "="  + obj.getId(); break;
			case "USER_ID": sql_value += seperator + param + "="  + obj.getUserId(); break;
			case "GROUP_ID": sql_value += seperator + param + "="  + obj.getGroupId(); break;
			case "TYPE": sql_value += seperator + param + "="  + obj.getType(); break;
			case "PRIORITY": sql_value += seperator + param + "="  + obj.getPriority(); break;
			case "DOC_ID": sql_value += seperator + param + "="  + obj.getDocId(); break;
			case "REPOS_ID": sql_value += seperator + param + "="  + obj.getReposId(); break;
			case "IS_ADMIN": sql_value += seperator + param + "="  + obj.getIsAdmin(); break;	
			case "ACCESS": sql_value += seperator + param + "="  + obj.getAccess(); break;
        	case "EDIT_EN": sql_value += seperator + param + "="  + obj.getEditEn(); break;
        	case "ADD_EN": sql_value += seperator + param + "="  + obj.getAddEn(); break;
        	case "DELETE_EN": sql_value += seperator + param + "="  + obj.getDeleteEn(); break;
        	case "HERITABLE": sql_value +=  + obj.getHeritable() + seperator; break;
        	case "DOC_PATH": sql_value += seperator + param + "= '" + obj.getDocPath() + "'"; break;
        	case "DOC_NAME": sql_value += seperator + param + "= '" + obj.getDocName() + "'"; break;
			}
		}
        sql = sql + sql_condition + sql_value;
        return sql;
	}

	private static Object buildDocLockFromJsonObj(JSONObject jsonObj) {
		DocLock obj = new DocLock();
		obj.setId( (Integer)jsonObj.get("id"));
		obj.setType((Integer)jsonObj.get("type"));
		obj.setName((String)jsonObj.get("name"));
		obj.setPath((String)jsonObj.get("path"));
		obj.setDocId( Long.parseLong(jsonObj.get("docId").toString()));
		obj.setPid( Long.parseLong(jsonObj.get("pid").toString()));
		obj.setVid( (Integer)jsonObj.get("vid"));
		obj.setState((Integer)jsonObj.get("state"));
		obj.setLockBy((Integer)jsonObj.get("lockBy"));
		obj.setLockTime( Long.parseLong(jsonObj.get("lockTime").toString()));
		return obj;
	}
	
	private static Object buildDocLockFromResultSet(ResultSet rs) throws SQLException {
		DocLock obj = new DocLock();
		obj.setId( rs.getInt("ID"));
		obj.setType(rs.getInt("TYPE"));
		obj.setName(rs.getString("NAME"));
		obj.setPath(rs.getString("PATH"));
		obj.setDocId( rs.getLong("DOC_ID"));
		obj.setPid( rs.getLong("PID"));
		obj.setVid( rs.getInt("VID"));
		obj.setState(rs.getInt("STATE"));
		obj.setLockBy(rs.getInt("LOCK_BY"));
		obj.setLockTime( rs.getLong("LOCK_TIME"));
		return obj;
	}

	private static List<String> buildParamListForDocLock(DocLock obj) {
		List<String> paramList = new ArrayList<String>();
		if(obj.getId( ) != null) { paramList.add("ID");}
		if(obj.getType() != null) { paramList.add("TYPE");}
		if(obj.getName() != null) { paramList.add("NAME");}
		if(obj.getPath() != null) { paramList.add("PATH");}
		if(obj.getDocId( ) != null) { paramList.add("DOC_ID");}
		if(obj.getPid( ) != null) { paramList.add("PID");}
		if(obj.getVid( ) != null) { paramList.add("VID");}
		if(obj.getState() != null) { paramList.add("STATE");}
		if(obj.getLockBy() != null) { paramList.add("LOCK_BY");}
		if(obj.getLockTime( ) != null) { paramList.add("LOCK_TIME");}
        return paramList;
	}
	
	
	private static String buildInsertSqlForDocLock(DocLock obj) {
		if(obj == null)
		{
			return 	null;
		}
		
		String sql_condition = "";
		String sql_value="";
		List<String> paramList = buildParamListForDocLock(obj);
		int lastParamIndex = paramList.size() - 1;
		for(int i=0; i < paramList.size(); i++)
		{
			String seperator = ",";
			String param = paramList.get(i);
			if(i == lastParamIndex)
			{
				seperator = "";
			}
			sql_condition += param + seperator;	//不带,
			
			switch(param)
			{			
			case "ID": sql_value += " " + obj.getId() + seperator; break;
			case "TYPE": sql_value += " " + obj.getType() + seperator; break;
			case "NAME": sql_value += " '" + obj.getName() + "'"  + seperator; break;
			case "PATH": sql_value += " '" + obj.getPath() + "'"  + seperator; break;
			case "DOC_ID": sql_value += " " + obj.getDocId() + seperator; break;
			case "PID": sql_value += " " + obj.getPid() + seperator; break;
			case "VID": sql_value += " " + obj.getVid() + seperator; break;
			case "STATE": sql_value += " " + obj.getState() + seperator; break;
			case "LOCK_BY": sql_value += " " + obj.getLockBy() + seperator; break;
			case "LOCK_TIME": sql_value += " " + obj.getLockTime() + seperator; break;
			}
		}
        String sql = "insert into DOC_LOCK (" + sql_condition + ")" + " values (" + sql_value + ")";
        return sql;
	}


	private static String buildQuerySqlForDocLock(DocLock obj, int objType) {
		String name = getNameByObjType(objType);
		String sql = "select * from " + name;
		
		if(obj == null)
		{
			return 	sql;
		}
		
		List<String> paramList = buildParamList(obj, objType);
		
		if(paramList == null)
		{
			return sql;
		}
		
		String sql_condition = " where ";
		String sql_value="";
		for(int i=0; i < paramList.size(); i++)
		{
			String seperator = " and ";
			String param = paramList.get(i);
			if(i == 0)
			{
				seperator = " ";
			}
			
			switch(param)
			{			
			case "ID": sql_value += seperator + param + "="  + obj.getId(); break;
			case "TYPE": sql_value += seperator + param + "="  + obj.getType(); break;
			case "NAME": sql_value += seperator + param + "='"  + obj.getName() + "'" ; break;
			case "PATH": sql_value += seperator + param + "='"  + obj.getPath() + "'" ; break;
			case "DOC_ID": sql_value += seperator + param + "="  + obj.getDocId(); break;
			case "PID": sql_value += seperator + param + "="  + obj.getPid(); break;
			case "VID": sql_value += seperator + param + "="  + obj.getVid(); break;
			case "STATE": sql_value += seperator + param + "="  + obj.getState(); break;
			case "LOCK_BY": sql_value += seperator + param + "="  + obj.getLockBy(); break;
			case "LOCK_TIME": sql_value += seperator + param + "="  + obj.getLockTime(); break;
			}
		}
        sql = sql + sql_condition + sql_value;
        return sql;
	}
	
	/****************************DocSys其他接口 *********************************/
	protected Integer getMaxFileSize() {
		// TODO Auto-generated method stub
		return null;
	}
	
	//获取当前登录用户信息
	protected User getCurrentUser(HttpSession session){
		User user = (User) session.getAttribute("login_user");
		System.out.println("get sessionId:"+session.getId());
		return user;
	}
	
	public static String getEmailProps(Object obj,String pName){
		Properties props = new Properties();
		String basePath = obj.getClass().getClassLoader().getResource("/").getPath();
		File config = new File(basePath+"emailConfig.properties");
		try {
			InputStream in = new FileInputStream(config);
			props.load(in);
			String pValue = (String) props.get(pName);
			return pValue;
		} catch (Exception e) {
			System.out.println("获取emailConfig.properties失败");
			return null;
		}	
	}
	
	Doc getDocByName(String name, Long parentId, Integer reposId)
	{
		Doc qdoc = new Doc();
		qdoc.setName(name);
		qdoc.setPid(parentId);
		qdoc.setVid(reposId);
		List <Doc> docList = reposService.getDocList(qdoc);
		if(docList != null && docList.size() > 0)
		{
			return docList.get(0);
		}
		return null;
	}
}
