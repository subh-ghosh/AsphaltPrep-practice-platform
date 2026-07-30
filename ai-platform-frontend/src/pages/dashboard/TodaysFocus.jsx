
import React, { useState, useEffect } from 'react';
import { Card, CardBody, Typography, Button, Chip, Progress } from "@material-tailwind/react";
import {
    CalendarDaysIcon,
    ChevronRightIcon,
    FireIcon,
    CheckCircleIcon,
    ArrowPathIcon
} from '@heroicons/react/24/solid';
import { useNavigate } from 'react-router-dom';
import api from "@/api";
import RecommendationCard from "@/components/RecommendationCard";

const TodaysFocus = ({ user }) => {
    const navigate = useNavigate();
    const [context, setContext] = useState(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        const fetchContext = async () => {
            try {
                const res = await api.get('/study-plans/active-context');
                setContext(res.data); // Returns empty object or { active: false } if no plan
            } catch (err) {
                console.error("Failed to load today's focus", err);
            } finally {
                setLoading(false);
            }
        };
        fetchContext();
    }, []);

    if (loading) return (
        <Card className="h-full border border-blue-100/60 dark:border-gray-800 shadow-sm bg-white/90 dark:bg-gray-900/60 backdrop-blur-md animate-pulse">
            <CardBody className="h-48 flex items-center justify-center">
                <ArrowPathIcon className="h-6 w-6 text-gray-300 animate-spin" />
            </CardBody>
        </Card>
    );

    // Empty State (No Active Plan)
    if (!context || !context.planId) {
        return (
            <Card className="h-full border border-blue-100/60 dark:border-gray-800 shadow-sm bg-white/90 dark:bg-gray-900/60 backdrop-blur-md">
                <CardBody className="flex flex-col items-center justify-center text-center h-full p-8">
                    <div className="p-4 bg-white dark:bg-gray-800 rounded-full shadow-sm mb-4">
                        <CalendarDaysIcon className="h-8 w-8 text-blue-500" />
                    </div>
                    <Typography variant="h5" color="blue-gray" className="mb-2 dark:text-white">
                        No Active Focus
                    </Typography>
                    <Typography color="gray" className="mb-6 font-normal max-w-xs dark:text-gray-400">
                        Create a study plan to get a personalized daily schedule and crush your goals.
                    </Typography>
                    <Button onClick={() => navigate('/dashboard/study-plan-builder')} color="blue" className="rounded-full">
                        Create Study Plan
                    </Button>
                </CardBody>
            </Card>
        );
    }

    const { planTitle, currentDay, totalDays, progress, todayItems = [], nextPractice } = context;
    const safeTodayItems = Array.isArray(todayItems) ? todayItems : [];
    const completedToday = safeTodayItems.filter(i => i && i.isCompleted).length;
    const totalToday = safeTodayItems.length;
    const isDayComplete = totalToday > 0 && completedToday === totalToday;

    return (
        <Card className="h-full border border-blue-100/60 dark:border-gray-800 shadow-sm bg-white/90 dark:bg-gray-900/60 backdrop-blur-md overflow-hidden">
            {/* Header - Glassy Dark Style */}
            <div className="bg-blue-600/10 dark:bg-blue-500/5 p-6 border-b border-blue-100/20 dark:border-white/5 relative overflow-hidden">
                <div className="absolute top-0 right-0 p-4 opacity-5 dark:opacity-10">
                    <CalendarDaysIcon className="h-32 w-32 transform rotate-12 -translate-y-8 translate-x-8" />
                </div>

                <div className="relative z-10">
                    <div className="flex justify-between items-start mb-4">
                        <Chip
                            value="Today's Focus"
                            size="sm"
                            variant="ghost"
                            className="bg-white/20 text-white border-0"
                            icon={<FireIcon className="text-orange-300" />}
                        />
                        <Typography variant="small" className="font-bold opacity-80">
                            Day {currentDay} of {totalDays}
                        </Typography>
                    </div>

                    <Typography variant="h4" color="blue-gray" className="mb-1 font-bold tracking-tight dark:text-white">
                        {planTitle}
                    </Typography>

                    <div className="flex items-center gap-2 mt-4">
                        <div className="flex-grow bg-white/20 rounded-full h-2 overflow-hidden">
                            <div
                                className="bg-white h-full rounded-full transition-all duration-500"
                                style={{ width: `${progress}%` }}
                            />
                        </div>
                        <Typography variant="small" className="font-bold">
                            {progress}%
                        </Typography>
                    </div>
                </div>
            </div>

            {/* Body */}
            <CardBody className="p-0 flex flex-col h-full bg-white/50 dark:bg-transparent">
                <div className="p-6 flex-grow">
                    <div className="flex justify-between items-center mb-4">
                        <Typography variant="h6" color="blue-gray" className="dark:text-white">
                            Your Schedule
                        </Typography>
                        <Typography variant="small" color="gray" className="font-normal dark:text-gray-400">
                            {completedToday}/{totalToday} Completed
                        </Typography>
                    </div>

                    <div className="space-y-3">
                        {safeTodayItems.map((item) => (
                            <div
                                key={item.itemId}
                                className={`flex items-center gap-3 p-3 rounded-xl border transition-colors ${item.isCompleted
                                    ? 'bg-green-50 border-green-100 dark:bg-green-900/20 dark:border-green-900/30'
                                    : 'bg-gray-50 border-gray-100 dark:bg-gray-800 dark:border-gray-700'
                                    }`}
                            >
                                <div className={`flex items-center justify-center h-8 w-8 rounded-full shrink-0 ${item.isCompleted ? 'bg-green-100 text-green-600' : 'bg-white text-gray-400 shadow-sm'
                                    }`}>
                                    {item.isCompleted ? <CheckCircleIcon className="h-5 w-5" /> : (
                                        <span className="text-xs font-bold">
                                            {item.type === 'VIDEO' ? '📺' : '📝'}
                                        </span>
                                    )}
                                </div>
                                <div className="flex-grow min-w-0">
                                    <Typography variant="small" color="blue-gray" className={`font-semibold truncate ${item.isCompleted ? 'line-through opacity-50' : ''} dark:text-gray-200`}>
                                        {item.title}
                                    </Typography>
                                    <Typography variant="small" color="gray" className="text-[10px] uppercase font-bold dark:text-gray-500">
                                        {item.type}
                                    </Typography>
                                </div>
                            </div>
                        ))}
                    </div>
                </div>

                {/* Footer Action */}
                <div className="p-4 border-t border-gray-100 dark:border-gray-800 bg-gray-50 dark:bg-white/5">
                    {isDayComplete ? (
                        <div className="text-center py-2">
                            <Typography color="green" className="font-bold flex items-center justify-center gap-2">
                                <CheckCircleIcon className="h-5 w-5" /> All tasks done for today!
                            </Typography>
                        </div>
                    ) : (
                        <Button
                            fullWidth
                            color="blue"
                            className="flex items-center justify-center gap-2 shadow-blue-500/20 hover:shadow-blue-500/40"
                            onClick={() => {
                                if (nextPractice) {
                                    // Go to practice page with next item
                                    navigate(`/dashboard/practice?subject=${encodeURIComponent(nextPractice.subject)}&topic=${encodeURIComponent(nextPractice.topic)}&difficulty=${encodeURIComponent(nextPractice.difficulty)}`);
                                } else {
                                    // Go to plan viewer
                                    navigate(`/dashboard/study-plans/${context.planId}`);
                                }
                            }}
                        >
                            {nextPractice ? `Practice: ${nextPractice.topic}` : "Continue Plan"}
                            <ChevronRightIcon className="h-4 w-4" />
                        </Button>
                    )}
                </div>
            </CardBody>
        </Card>
    );
};

export default TodaysFocus;
