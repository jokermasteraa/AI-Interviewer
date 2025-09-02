package com.axle.service;

import com.axle.bo.QuestionLibBO;
import com.axle.utils.PagedGridResult;

public interface QuestionLibService {
    public void createOrUpdate(QuestionLibBO questionLibBO);

    public PagedGridResult queryQuestionLibList(String aiName, String question, Integer page, Integer pageSize);

    public  void showOrHide(String questionLibId, Integer isOn);

    void deleteById(String questionLibId);
}
